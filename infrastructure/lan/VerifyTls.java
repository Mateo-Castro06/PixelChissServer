import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;

/** Runs in the existing JDK image, without network. Never prints key/password data. */
class VerifyTls {
    public static void main(String[] args) {
        String result = "TLS_INVALID";
        try {
            Path dir = Path.of("/tls");
            char[] password = System.getenv("LAN_TLS_PASSWORD").toCharArray();
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(dir.resolve("server.p12"))) { store.load(input, password); }
            var factory = CertificateFactory.getInstance("X.509");
            X509Certificate ca;
            try (var input = Files.newInputStream(dir.resolve("ca.crt"))) { ca = (X509Certificate)factory.generateCertificate(input); }
            try { ca.checkValidity(); } catch (CertificateException e) { throw new Failure("TLS_CA_EXPIRED"); }
            if (ca.getBasicConstraints() < 0) throw new Failure("TLS_CHAIN");
            var leaf = (X509Certificate)store.getCertificate("server");
            var key = store.getKey("server", password);
            if (!(key instanceof PrivateKey) || leaf == null) throw new Failure("TLS_KEY");
            var signature = Signature.getInstance("SHA256withRSA");
            byte[] challenge = new byte[32]; new SecureRandom().nextBytes(challenge);
            signature.initSign((PrivateKey)key); signature.update(challenge); byte[] signed = signature.sign();
            signature.initVerify(leaf.getPublicKey()); signature.update(challenge);
            if (!signature.verify(signed)) throw new Failure("TLS_KEY");
            try (var input = Files.newInputStream(dir.resolve("server.crt"))) {
                if (!leaf.equals(factory.generateCertificate(input))) throw new Failure("TLS_KEY");
            }
            // Signature is checked before renewable failures: a foreign CA is never renewed silently.
            try { leaf.verify(ca.getPublicKey()); } catch (GeneralSecurityException e) { throw new Failure("TLS_CHAIN"); }
            if (!leaf.getIssuerX500Principal().equals(ca.getSubjectX500Principal())) throw new Failure("TLS_CHAIN");
            var usage = leaf.getExtendedKeyUsage();
            if (usage == null || !usage.contains("1.3.6.1.5.5.7.3.1")) throw new Failure("TLS_USAGE");
            try { leaf.checkValidity(); } catch (CertificateExpiredException e) { throw new Failure("TLS_EXPIRED"); }
            catch (CertificateNotYetValidException e) { throw new Failure("TLS_NOT_YET_VALID"); }
            var parameters = new PKIXParameters(Set.of(new TrustAnchor(ca, null)));
            parameters.setRevocationEnabled(false); // Private offline CA has no configured OCSP/CRL service.
            CertPathValidator.getInstance("PKIX").validate(factory.generateCertPath(List.of(leaf)), parameters);
            boolean matches = false;
            var sans = leaf.getSubjectAlternativeNames();
            if (sans != null) for (var san : sans) {
                if (Integer.valueOf(7).equals(san.get(0)) && args[0].equals(san.get(1))) matches = true;
            }
            if (!matches) throw new Failure("TLS_IP");
            result = "OK";
        } catch (Failure failure) { result = failure.getMessage(); }
        catch (Exception ignored) { /* Return a sanitized status, without keystore exceptions. */ }
        System.out.println(result);
        if (!result.equals("OK")) System.exit(2);
    }
    private static class Failure extends Exception { Failure(String value) { super(value); } }
}
