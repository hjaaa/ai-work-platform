/*
 *
 *      Copyright (c) 2018-2025, lengleng All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 * Neither the name of the pig4cloud.com developer nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 * Author: lengleng (wangiegie@gmail.com)
 *
 */

package com.aiwork.baas.provision;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * DDL 标识符白名单(spec §12.2)：正则加 MySQL 保留字。
 * DDL 标识符不能参数化，本类是唯一防线。
 *
 * @author ai-work
 * @date 2026/07/17
 */
public final class IdentifierValidator {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    private static final String RESERVED_WORDS_RESOURCE = "/mysql-reserved-words.txt";

    private static final Set<String> RESERVED_WORDS = loadReservedWords();

    private IdentifierValidator() {
    }

    public static void validate(String identifier) {
        if (identifier == null || !IDENTIFIER_PATTERN.matcher(identifier).matches()
                || RESERVED_WORDS.contains(identifier)) {
            throw new IllegalArgumentException("illegal identifier");
        }
    }

    private static Set<String> loadReservedWords() {
        Set<String> words = new HashSet<>();
        InputStream resourceStream = IdentifierValidator.class.getResourceAsStream(RESERVED_WORDS_RESOURCE);
        if (resourceStream == null) {
            throw new IllegalStateException("mysql-reserved-words.txt 加载失败");
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resourceStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String word = line.trim().toLowerCase();
                if (!word.isEmpty()) {
                    words.add(word);
                }
            }
        }
        catch (Exception e) {
            throw new IllegalStateException("mysql-reserved-words.txt 加载失败", e);
        }
        return Set.copyOf(words);
    }

}
