package com.ai.spring_lens.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * One-time utility to generate BCrypt password hashes for DB seed data.
 * Run once, copy output into Flyway migration, then delete this class.
 * Never commit this file.
 */
public class PasswordHashGenerator {

    public static void main(String[] args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
        System.out.println("=== BCrypt Hashes (cost=12) ===");
        System.out.println("Admin@123 -> " + encoder.encode("Admin@123"));
        System.out.println("User@123  -> " + encoder.encode("User@123"));
        System.out.println("==============================");
    }
}