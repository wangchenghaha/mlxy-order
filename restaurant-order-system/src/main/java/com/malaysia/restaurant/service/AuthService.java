package com.malaysia.restaurant.service;

import com.malaysia.restaurant.common.enums.Role;
import com.malaysia.restaurant.entity.Domain;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AuthService {
    private final InMemoryStore store;
    private final PasswordHasher hasher;
    private final StringRedisTemplate redis;
    private final String secret;
    private final int tokenHours;
    private final boolean smsDebugEnabled;
    private final String appLatestVersion;
    private final int appLatestBuild;
    private final String appDownloadUrl;
    private final String appReleaseNotes;
    private final boolean appForceUpdate;
    private final SecureRandom random = new SecureRandom();

    public AuthService(InMemoryStore store, PasswordHasher hasher, StringRedisTemplate redis,
                       @Value("${restaurant.security.jwt-secret}") String secret,
                       @Value("${restaurant.security.token-hours:12}") int tokenHours,
                       @Value("${restaurant.security.sms-debug-enabled:false}") boolean smsDebugEnabled,
                       @Value("${restaurant.app.latest-version:1.0.1}") String appLatestVersion,
                       @Value("${restaurant.app.latest-build:2}") int appLatestBuild,
                       @Value("${restaurant.app.download-url:}") String appDownloadUrl,
                       @Value("${restaurant.app.release-notes:优化桌台状态同步、弱网重连和个人资料体验}") String appReleaseNotes,
                       @Value("${restaurant.app.force-update:false}") boolean appForceUpdate) {
        this.store = store;
        this.hasher = hasher;
        this.redis = redis;
        this.secret = secret;
        this.tokenHours = tokenHours;
        this.smsDebugEnabled = smsDebugEnabled;
        this.appLatestVersion = appLatestVersion;
        this.appLatestBuild = appLatestBuild;
        this.appDownloadUrl = appDownloadUrl;
        this.appReleaseNotes = appReleaseNotes;
        this.appForceUpdate = appForceUpdate;
    }

    public CaptchaChallenge captcha() {
        int left = random.nextInt(8) + 2;
        int right = random.nextInt(8) + 1;
        String id = UUID.randomUUID().toString();
        redis.opsForValue().set(captchaKey(id), String.valueOf(left + right), Duration.ofMinutes(5));
        return new CaptchaChallenge(id, left + " + " + right + " = ?", 300);
    }

    public SmsCodeResult sendSmsCode(String phone) {
        if (phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("手机号不能为空");
        }
        Domain.User user = findUserByPhone(phone);
        if (!user.enabled()) {
            throw new IllegalArgumentException("账号已禁用");
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        redis.opsForValue().set(smsKey(phone), code, Duration.ofMinutes(5));
        return new SmsCodeResult(maskPhone(phone), 300, smsDebugEnabled ? code : null);
    }

    public LoginResult loginByPassword(String username, String password, String captchaId, String captchaCode) {
        verifyChallenge(captchaKey(captchaId), captchaCode, "验证码错误或已过期");
        Domain.User user = store.users.values().stream()
                .filter(u -> u.username().equals(username) || u.phone().equals(username))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("账号不存在"));
        if (!user.enabled()) {
            throw new IllegalArgumentException("账号已禁用");
        }
        if (user.lockedUntil() != null && user.lockedUntil().isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("账号已锁定，请稍后再试");
        }
        if (!hasher.matches(password, user.passwordHash())) {
            int failCount = user.failCount() + 1;
            LocalDateTime locked = failCount >= 5 ? LocalDateTime.now().plusMinutes(15) : null;
            store.saveUser(user.withFailCount(failCount, locked));
            throw new IllegalArgumentException("密码错误");
        }
        Domain.User clean = user.withFailCount(0, null);
        if (hasher.needsUpgrade(clean.passwordHash())) {
            clean = clean.withPasswordHash(hasher.hash(password));
        }
        store.saveUser(clean);
        Domain.UserMembership membership = defaultMembership(clean.id());
        return new LoginResult(createToken(clean, membership), clean.id(), membership.merchantId(), membership.storeId(),
                membership.id(), clean.displayName(), clean.avatarUrl(), membership.role());
    }

    public LoginResult loginBySms(String phone, String smsCode) {
        verifyChallenge(smsKey(phone), smsCode, "短信验证码错误或已过期");
        Domain.User user = findUserByPhone(phone);
        if (!user.enabled()) {
            throw new IllegalArgumentException("账号已禁用");
        }
        if (user.lockedUntil() != null && user.lockedUntil().isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("账号已锁定，请稍后再试");
        }
        Domain.User clean = user.withFailCount(0, null);
        store.saveUser(clean);
        Domain.UserMembership membership = defaultMembership(clean.id());
        return new LoginResult(createToken(clean, membership), clean.id(), membership.merchantId(), membership.storeId(),
                membership.id(), clean.displayName(), clean.avatarUrl(), membership.role());
    }

    public LoginResult switchMembership(String authHeader, long membershipId) {
        AuthContext context = parse(authHeader);
        Domain.UserMembership membership = store.memberships.get(membershipId);
        if (membership == null || membership.userId() != context.id() || !membership.active()) {
            throw new IllegalArgumentException("当前身份不可用");
        }
        return new LoginResult(createToken(context.user(), membership), context.id(), membership.merchantId(),
                membership.storeId(), membership.id(), context.displayName(), context.avatarUrl(), membership.role());
    }

    public AuthContext require(String authHeader, Role... roles) {
        AuthContext context = parse(authHeader);
        Set<Role> allowed = Set.of(roles);
        if (!allowed.contains(context.role()) && context.role() != Role.PLATFORM_SUPER_ADMIN) {
            throw new IllegalArgumentException("无权限操作");
        }
        return context;
    }

    public AuthContext parse(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("请先登录");
        }
        String token = authHeader.substring("Bearer ".length());
        String[] chunks = token.split("\\.");
        if (chunks.length != 2) {
            throw new IllegalArgumentException("Token格式错误");
        }
        String payload = new String(Base64.getUrlDecoder().decode(chunks[0]), StandardCharsets.UTF_8);
        if (!sign(payload).equals(chunks[1])) {
            throw new IllegalArgumentException("Token签名无效");
        }
        String[] parts = payload.split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Token已失效，请重新登录");
        }
        long userId = Long.parseLong(parts[0]);
        long membershipId = Long.parseLong(parts[1]);
        long expires = Long.parseLong(parts[2]);
        if (expires < System.currentTimeMillis()) {
            throw new IllegalArgumentException("登录已过期");
        }
        Domain.User user = store.users.get(userId);
        if (user == null || !user.enabled()) {
            throw new IllegalArgumentException("账号不可用");
        }
        Domain.UserMembership membership = store.memberships.get(membershipId);
        if (membership == null || membership.userId() != user.id() || !membership.active()) {
            throw new IllegalArgumentException("当前身份不可用");
        }
        return new AuthContext(user, membership);
    }

    public EventTicket issueEventTicket(String authHeader) {
        AuthContext context = parse(authHeader);
        String ticket = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set(eventTicketKey(ticket), context.tokenSubject(), Duration.ofMinutes(10));
        return new EventTicket(ticket, 600);
    }

    public UserProfile profile(String authHeader) {
        AuthContext context = parse(authHeader);
        return toProfile(context.user(), context.membership());
    }

    public UserProfile updateProfile(String authHeader, String phone, String displayName, String avatarUrl) {
        AuthContext context = parse(authHeader);
        Domain.User user = context.user();
        String nextPhone = phone == null || phone.isBlank() ? user.phone() : phone.trim();
        String nextName = displayName == null || displayName.isBlank() ? user.displayName() : displayName.trim();
        String nextAvatar = avatarUrl == null || avatarUrl.isBlank() ? user.avatarUrl() : avatarUrl.trim();
        Domain.User next = user.withProfile(nextPhone, nextName, nextAvatar);
        store.saveUser(next);
        return toProfile(next, context.membership());
    }

    public void changePassword(String authHeader, String oldPassword, String newPassword, String confirmPassword) {
        AuthContext context = parse(authHeader);
        if (oldPassword == null || oldPassword.isBlank()) {
            throw new IllegalArgumentException("旧密码不能为空");
        }
        if (newPassword == null || newPassword.isBlank()) {
            throw new IllegalArgumentException("新密码不能为空");
        }
        String trimmedNewPassword = newPassword.trim();
        if (trimmedNewPassword.length() < 6 || trimmedNewPassword.length() > 20) {
            throw new IllegalArgumentException("新密码长度需在6到20个字符之间");
        }
        if (confirmPassword == null || confirmPassword.isBlank()) {
            throw new IllegalArgumentException("确认密码不能为空");
        }
        if (!trimmedNewPassword.equals(confirmPassword.trim())) {
            throw new IllegalArgumentException("两次输入密码不一致");
        }
        Domain.User user = context.user();
        if (!hasher.matches(oldPassword, user.passwordHash())) {
            throw new IllegalArgumentException("旧密码错误");
        }
        if (hasher.matches(trimmedNewPassword, user.passwordHash())) {
            throw new IllegalArgumentException("新密码不能与旧密码相同");
        }
        Domain.User next = user.withPasswordHash(hasher.hash(trimmedNewPassword)).withFailCount(0, null);
        store.saveUser(next);
    }

    public AppUpdateInfo checkAppUpdate(String platform, String version, Integer build) {
        int currentBuild = build == null ? 0 : build;
        boolean hasUpdate = currentBuild > 0 ? appLatestBuild > currentBuild : !appLatestVersion.equalsIgnoreCase(version == null ? "" : version);
        return new AppUpdateInfo(hasUpdate, appLatestVersion, appLatestBuild, appDownloadUrl, appReleaseNotes, appForceUpdate,
                platform == null || platform.isBlank() ? "app" : platform);
    }

    private UserProfile toProfile(Domain.User user, Domain.UserMembership membership) {
        String roleName = membership.role().name();
        return new UserProfile(user.id(), user.username(), user.phone(), user.displayName(), user.avatarUrl(), roleName,
                membership.merchantId(), membership.storeId());
    }

    public AuthContext parseEventTicket(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            throw new IllegalArgumentException("实时连接凭证无效");
        }
        String subject = redis.opsForValue().get(eventTicketKey(ticket));
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("实时连接凭证已过期");
        }
        String[] parts = subject.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("实时连接凭证无效");
        }
        long userId = Long.parseLong(parts[0]);
        long membershipId = Long.parseLong(parts[1]);
        Domain.User user = store.users.get(userId);
        Domain.UserMembership membership = store.memberships.get(membershipId);
        if (user == null || !user.enabled() || membership == null || membership.userId() != user.id() || !membership.active()) {
            throw new IllegalArgumentException("当前身份不可用");
        }
        return new AuthContext(user, membership);
    }

    private String createToken(Domain.User user, Domain.UserMembership membership) {
        String payload = user.id() + ":" + membership.id() + ":" + (System.currentTimeMillis() + tokenHours * 3600_000L);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + sign(payload);
    }

    private Domain.UserMembership defaultMembership(long userId) {
        List<Domain.UserMembership> matches = store.memberships.values().stream()
                .filter(m -> m.userId() == userId && m.active())
                .sorted(Comparator.comparing(Domain.UserMembership::id))
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("账号未绑定商户或门店");
        }
        return matches.get(0);
    }

    private Domain.User findUserByPhone(String phone) {
        return store.users.values().stream()
                .filter(u -> u.phone().equals(phone))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("手机号未注册"));
    }

    private void verifyChallenge(String key, String code, String message) {
        if (key == null || key.isBlank() || code == null || code.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        String saved = redis.opsForValue().get(key);
        if (saved == null || !saved.equalsIgnoreCase(code.trim())) {
            throw new IllegalArgumentException(message);
        }
        redis.delete(key);
    }

    private String captchaKey(String id) {
        return "restaurant:auth:captcha:" + id;
    }

    private String smsKey(String phone) {
        return "restaurant:auth:sms:" + phone;
    }

    private String eventTicketKey(String ticket) {
        return "restaurant:auth:sse-ticket:" + ticket;
    }

    private String maskPhone(String phone) {
        if (phone.length() <= 6) return phone;
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 3);
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record LoginResult(String token, long userId, Long merchantId, Long storeId, long membershipId,
                              String displayName, String avatarUrl, Role role) {
    }

    public record CaptchaChallenge(String captchaId, String question, int expiresInSeconds) {
    }

    public record SmsCodeResult(String phone, int expiresInSeconds, String debugCode) {
    }

    public record EventTicket(String ticket, int expiresInSeconds) {
    }

    public record UserProfile(long userId, String username, String phone, String displayName, String avatarUrl,
                              String role, Long merchantId, Long storeId) {
    }

    public record AppUpdateInfo(boolean hasUpdate, String latestVersion, int latestBuild, String downloadUrl,
                                String releaseNotes, boolean forceUpdate, String platform) {
    }

    public record AuthContext(Domain.User user, Domain.UserMembership membership) {
        public long id() {
            return user.id();
        }

        public String displayName() {
            return user.displayName();
        }

        public String avatarUrl() {
            return user.avatarUrl();
        }

        public Role role() {
            return membership.role();
        }

        public Long merchantId() {
            return membership.merchantId();
        }

        public Long storeId() {
            return membership.storeId();
        }

        public long requiredMerchantId() {
            if (merchantId() == null) throw new IllegalArgumentException("当前身份未绑定商户");
            return merchantId();
        }

        public long requiredStoreId() {
            if (storeId() == null) throw new IllegalArgumentException("当前身份未绑定门店");
            return storeId();
        }

        public String tokenSubject() {
            return user.id() + ":" + membership.id();
        }
    }

    @Component
    public static class PasswordHasher {
        private static final String BCRYPT_PREFIX = "{bcrypt}";
        private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

        public String hash(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("密码不能为空");
            }
            return BCRYPT_PREFIX + bcrypt.encode(raw);
        }

        public boolean matches(String raw, String hashed) {
            if (raw == null || hashed == null || hashed.isBlank()) {
                return false;
            }
            if (hashed.startsWith(BCRYPT_PREFIX)) {
                return bcrypt.matches(raw, hashed.substring(BCRYPT_PREFIX.length()));
            }
            if (hashed.startsWith("$2a$") || hashed.startsWith("$2b$") || hashed.startsWith("$2y$")) {
                return bcrypt.matches(raw, hashed);
            }
            return legacySha256(raw).equals(hashed);
        }

        public boolean needsUpgrade(String hashed) {
            return hashed == null || !(hashed.startsWith(BCRYPT_PREFIX)
                    || hashed.startsWith("$2a$") || hashed.startsWith("$2b$") || hashed.startsWith("$2y$"));
        }

        private String legacySha256(String raw) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
                return Base64.getEncoder().encodeToString(bytes);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
