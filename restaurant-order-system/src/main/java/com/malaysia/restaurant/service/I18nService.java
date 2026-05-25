package com.malaysia.restaurant.service;

import com.malaysia.restaurant.entity.Domain;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class I18nService {
    private final InMemoryStore store;

    public I18nService(InMemoryStore store) {
        this.store = store;
    }

    public Map<String, String> list(String lang) {
        Map<String, String> result = new LinkedHashMap<>();
        store.i18n.values().stream()
                .sorted((a, b) -> a.key().compareToIgnoreCase(b.key()))
                .forEach(item -> result.put(item.key(), item.value(lang)));
        return result;
    }

    public Domain.SysI18n save(String key, String zhCn, String enUs, String msMy, String remark) {
        return store.saveI18n(key, zhCn, enUs, msMy, remark);
    }
}
