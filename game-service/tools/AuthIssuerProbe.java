import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/** Loads the existing compiled Auth issuer, using an isolated validation key only. */
public class AuthIssuerProbe {
    public static void main(String[] args) throws Exception {
        Object issuer = Class.forName("com.pixelchiss.auth.security.JwtService")
                .getConstructor(String.class).newInstance(args[0]);
        var mint = issuer.getClass().getMethod("generateAccessToken", long.class, String.class);
        String pem = Files.readString(Path.of(args[1])).replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
        var key = (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
        var decoder = com.pixelchiss.gameservice.config.JwtConfig.decoder(key);
        var tokens = new Properties();
        for (long id : new long[]{900001, 900002, 900003}) {
            String token = (String) mint.invoke(issuer, id, "validation@example.invalid");
            var decoded = decoder.decode(token);
            if (!decoded.getSubject().equals(Long.toString(id)) || decoded.getExpiresAt() == null)
                throw new AssertionError("Auth issuer incompatible with Game decoder");
            tokens.setProperty(Long.toString(id), token);
        }
        try (var output = Files.newOutputStream(Path.of(args[2]))) {
            tokens.store(output, "Ephemeral validation credentials; never production credentials");
        }
        System.out.println("AUTH_ISSUER_PROBE_OK: existing Auth JwtService -> Game JwtDecoder; isolated RSA key");
    }
}
