package com.malaysia.restaurant.controller.common;

import com.malaysia.restaurant.common.result.ApiResponse;
import com.malaysia.restaurant.service.AuthService;
import com.malaysia.restaurant.service.I18nService;
import com.malaysia.restaurant.service.RealtimeEventService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/common")
public class CommonController {
    private final AuthService authService;
    private final I18nService i18nService;
    private final RealtimeEventService realtime;

    public CommonController(AuthService authService, I18nService i18nService, RealtimeEventService realtime) {
        this.authService = authService;
        this.i18nService = i18nService;
        this.realtime = realtime;
    }

    @PostMapping("/auth/login")
    public ApiResponse<AuthService.LoginResult> login(@RequestBody LoginRequest request) {
        if ("SMS".equalsIgnoreCase(request.loginType())) {
            return ApiResponse.ok(authService.loginBySms(request.phone(), request.smsCode()));
        }
        return ApiResponse.ok(authService.loginByPassword(request.username(), request.password(),
                request.captchaId(), request.captchaCode()));
    }

    @GetMapping("/auth/captcha")
    public ResponseEntity<ApiResponse<AuthService.CaptchaChallenge>> captcha() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(authService.captcha()));
    }

    @PostMapping("/auth/sms-code")
    public ApiResponse<AuthService.SmsCodeResult> smsCode(@RequestBody SmsCodeRequest request) {
        return ApiResponse.ok(authService.sendSmsCode(request.phone()));
    }

    @GetMapping("/auth/profile")
    public ApiResponse<AuthService.UserProfile> profile(@RequestHeader("Authorization") String token) {
        return ApiResponse.ok(authService.profile(token));
    }

    @PutMapping("/auth/profile")
    public ApiResponse<AuthService.UserProfile> updateProfile(@RequestHeader("Authorization") String token,
                                                              @RequestBody ProfileRequest request) {
        return ApiResponse.ok(authService.updateProfile(token, request.phone(), request.displayName(), request.avatarUrl()));
    }

    @PutMapping("/auth/password")
    public ApiResponse<Boolean> changePassword(@RequestHeader("Authorization") String token,
                                               @RequestBody PasswordChangeRequest request) {
        authService.changePassword(token, request.oldPassword(), request.newPassword(), request.confirmPassword());
        return ApiResponse.ok(true);
    }

    @PostMapping("/auth/avatar")
    public ApiResponse<AvatarUploadResult> uploadAvatar(@RequestHeader("Authorization") String token,
                                                        @RequestPart("file") MultipartFile file) throws IOException {
        AuthService.AuthContext context = authService.parse(token);
        UploadedImage image = uploadImage(file, "avatar", context.id(), "头像文件不能为空",
                "仅支持 JPG、PNG、WEBP 头像", "头像不能超过2MB");
        return ApiResponse.ok(new AvatarUploadResult(image.url()));
    }

    @PostMapping("/upload/dish-image")
    public ApiResponse<FileUploadResult> uploadDishImage(@RequestHeader("Authorization") String token,
                                                         @RequestPart("file") MultipartFile file) throws IOException {
        AuthService.AuthContext context = authService.parse(token);
        UploadedImage image = uploadImage(file, "dishes", context.id(), "菜品图片不能为空",
                "仅支持 JPG、PNG、WEBP 菜品图片", "菜品图片不能超过2MB");
        return ApiResponse.ok(new FileUploadResult(image.url()));
    }

    private UploadedImage uploadImage(MultipartFile file, String folder, Long ownerId, String emptyMessage,
                                      String typeMessage, String sizeMessage) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(emptyMessage);
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!Set.of("image/jpeg", "image/png", "image/webp").contains(contentType)) {
            throw new IllegalArgumentException(typeMessage);
        }
        if (file.getSize() > 2 * 1024 * 1024L) {
            throw new IllegalArgumentException(sizeMessage);
        }
        String extension = switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
        Path directory = Path.of("uploads", folder).toAbsolutePath();
        Files.createDirectories(directory);
        String filename = ownerId + "-" + UUID.randomUUID().toString().replace("-", "") + extension;
        Path target = directory.resolve(filename);
        file.transferTo(target.toFile());
        return new UploadedImage("/uploads/" + folder + "/" + filename);
    }

    @GetMapping("/app/update")
    public ApiResponse<AuthService.AppUpdateInfo> appUpdate(@RequestParam(defaultValue = "waiter_app") String platform,
                                                            @RequestParam(defaultValue = "1.0.0") String version,
                                                            @RequestParam(required = false) Integer build) {
        return ApiResponse.ok(authService.checkAppUpdate(platform, version, build));
    }

    @GetMapping("/i18n/list")
    public ApiResponse<Map<String, String>> i18n(@RequestParam(defaultValue = "ms_my") String lang) {
        return ApiResponse.ok(i18nService.list(lang));
    }

    @PostMapping("/events/ticket")
    public ApiResponse<AuthService.EventTicket> eventTicket(@RequestHeader("Authorization") String token) {
        return ApiResponse.ok(authService.issueEventTicket(token));
    }

    @GetMapping("/events")
    public SseEmitter events(@RequestParam(required = false) String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return eventError("实时连接凭证缺失");
        }
        try {
            return realtime.subscribe(authService.parseEventTicket(ticket));
        } catch (IllegalArgumentException ex) {
            return eventError(ex.getMessage());
        }
    }

    private SseEmitter eventError(String message) {
        SseEmitter emitter = new SseEmitter(1000L);
        try {
            emitter.send(SseEmitter.event().name("ERROR").data(Map.of("message", message)));
        } catch (Exception ignored) {
        }
        emitter.complete();
        return emitter;
    }

    public record LoginRequest(String loginType, String username, String password, String captchaId, String captchaCode,
                               String phone, String smsCode) {
    }

    public record SmsCodeRequest(String phone) {
    }

    public record ProfileRequest(String phone, String displayName, String avatarUrl) {
    }

    public record PasswordChangeRequest(String oldPassword, String newPassword, String confirmPassword) {
    }

    public record AvatarUploadResult(String avatarUrl) {
    }

    public record FileUploadResult(String url) {
    }

    private record UploadedImage(String url) {
    }
}
