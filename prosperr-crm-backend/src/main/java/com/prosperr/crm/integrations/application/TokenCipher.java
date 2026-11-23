package com.prosperr.crm.integrations.application;

import com.prosperr.crm.shared.config.CrmProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/** AES-256-GCM for stored refresh tokens and HMAC for the OAuth state, both keyed by ENCRYPTION_KEY. */
@Component
public class TokenCipher {
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public TokenCipher(CrmProperties props) {
        String hex = props.google() == null ? null : props.google().encryptionKey();
        this.key = hex == null || hex.isBlank() ? null : HexFormat.of().parseHex(hex.trim());
        if (key != null && key.length != 32) throw new IllegalStateException("ENCRYPTION_KEY must be 64 hex characters (32 bytes)");
    }

    public boolean ready() { return key != null; }

    public byte[] encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
        } catch (Exception e) { throw new IllegalStateException("Could not encrypt token", e); }
    }

    public String decrypt(byte[] blob) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, blob, 0, 12));
            return new String(c.doFinal(blob, 12, blob.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("Could not decrypt token", e); }
    }

    public byte[] sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public boolean verify(String data, byte[] sig) { return MessageDigest.isEqual(sign(data), sig); }
}
