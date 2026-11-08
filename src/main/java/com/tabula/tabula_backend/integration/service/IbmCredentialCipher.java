package com.tabula.tabula_backend.integration.service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** AES-256-GCM, random nonce and tenant/record-bound authenticated data. No plaintext persistence. */
@Component
public class IbmCredentialCipher {
    private final String configuredKey;
    private final SecureRandom random = new SecureRandom();
    public IbmCredentialCipher(@Value("${ibm.watson.credentials-encryption-key:${IBM_CREDENTIAL_ENCRYPTION_KEY:}}") String key) { this.configuredKey = key; }
    public String encrypt(String value, String context) {
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            var cipher = cipher(Cipher.ENCRYPT_MODE, nonce, context);
            return "v1." + Base64.getEncoder().encodeToString(nonce) + "." + Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (IntegrationException ex) { throw ex; }
        catch (Exception ex) { throw unavailable(); }
    }
    public String decrypt(String value, String context) {
        try {
            var parts = value.split("\\.");
            if (parts.length != 3 || !"v1".equals(parts[0])) throw unavailable();
            byte[] nonce = Base64.getDecoder().decode(parts[1]); if (nonce.length != 12) throw unavailable();
            return new String(cipher(Cipher.DECRYPT_MODE, nonce, context).doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (IntegrationException ex) { throw ex; }
        catch (Exception ex) { throw unavailable(); }
    }
    private Cipher cipher(int mode, byte[] nonce, String context) throws Exception {
        byte[] key;
        try { key = Base64.getDecoder().decode(configuredKey); }
        catch (RuntimeException ex) { throw unavailable(); }
        if (key.length != 32) throw unavailable();
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
    private IntegrationException unavailable() { return new IntegrationException(HttpStatus.SERVICE_UNAVAILABLE, "IBM_CREDENTIAL_ENCRYPTION_UNAVAILABLE", "Chave de proteção de credenciais ausente, inválida ou incompatível."); }
}
