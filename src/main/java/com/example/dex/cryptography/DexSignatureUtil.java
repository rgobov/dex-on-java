package com.example.dex.cryptography;

import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Утилита для создания и проверки цифровых подписей (SHA256withRSA).
 */
public final class DexSignatureUtil {

    private DexSignatureUtil() {}

    /**
     * Подписывает строку данных с использованием приватного ключа.
     */
    public static String sign(String data, PrivateKey privateKey) throws Exception {
        Signature privateSignature = Signature.getInstance("SHA256withRSA");
        privateSignature.initSign(privateKey);
        privateSignature.update(data.getBytes("UTF-8"));
        byte[] signatureBytes = privateSignature.sign();
        return Base64.getEncoder().encodeToString(signatureBytes);
    }

    /**
     * Проверяет подпись строки данных с использованием публичного ключа.
     */
    public static boolean verify(String data, String signatureBase64, PublicKey publicKey) {
        try {
            Signature publicSignature = Signature.getInstance("SHA256withRSA");
            publicSignature.initVerify(publicKey);
            publicSignature.update(data.getBytes("UTF-8"));
            byte[] signatureBytes = Base64.getDecoder().decode(signatureBase64);
            return publicSignature.verify(signatureBytes);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Генерирует пару RSA-ключей.
     */
    public static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /**
     * Преобразует публичный ключ в строку Base64.
     */
    public static String encodePublicKey(PublicKey publicKey) {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    /**
     * Восстанавливает публичный ключ из строки Base64.
     */
    public static PublicKey decodePublicKey(String keyStr) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(keyStr);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePublic(spec);
    }

    /**
     * Преобразует приватный ключ в строку Base64.
     */
    public static String encodePrivateKey(PrivateKey privateKey) {
        return Base64.getEncoder().encodeToString(privateKey.getEncoded());
    }

    /**
     * Восстанавливает приватный ключ из строки Base64.
     */
    public static PrivateKey decodePrivateKey(String keyStr) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(keyStr);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(spec);
    }
}
