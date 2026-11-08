package com.tabula.tabula_backend.integration;

import com.tabula.tabula_backend.integration.service.*;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class IbmCredentialCipherTests {
    private final IbmCredentialCipher cipher = new IbmCredentialCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    @Test void freshNoncesProduceDifferentCiphertextsAndPreserveSecret() {
        String first = cipher.encrypt("fake-secret", "portfolio:integration:endpoint");
        String second = cipher.encrypt("fake-secret", "portfolio:integration:endpoint");
        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first, "portfolio:integration:endpoint")).isEqualTo("fake-secret");
        assertThat(first).doesNotContain("fake-secret");
    }
    @Test void changedCiphertextContextOrMasterKeyFailsAuthentication() {
        String encrypted = cipher.encrypt("fake-secret", "a");
        var parts = encrypted.split("\\."); byte[] bytes = Base64.getDecoder().decode(parts[2]); bytes[0] ^= 1;
        String changed = parts[0]+"."+parts[1]+"."+Base64.getEncoder().encodeToString(bytes);
        assertThatThrownBy(() -> cipher.decrypt(changed, "a")).isInstanceOf(IntegrationException.class);
        assertThatThrownBy(() -> cipher.decrypt(encrypted, "b")).isInstanceOf(IntegrationException.class);
        byte[] other = new byte[32]; other[0] = 1;
        assertThatThrownBy(() -> new IbmCredentialCipher(Base64.getEncoder().encodeToString(other)).decrypt(encrypted, "a")).isInstanceOf(IntegrationException.class);
    }
}
