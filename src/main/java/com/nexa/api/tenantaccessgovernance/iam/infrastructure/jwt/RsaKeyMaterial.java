package com.nexa.api.tenantaccessgovernance.iam.infrastructure.jwt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class RsaKeyMaterial {
	private RsaKeyMaterial() {}

	public static RSAPrivateKey readPrivate(String path) {
		try {
			byte[] der = decodePem(Path.of(path), "PRIVATE KEY");
			return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
		} catch (IOException | GeneralSecurityException exception) {
			throw new IllegalStateException("Unable to load RSA private key", exception);
		}
	}

	public static RSAPublicKey readPublic(String path) {
		try {
			byte[] der = decodePem(Path.of(path), "PUBLIC KEY");
			return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
		} catch (IOException | GeneralSecurityException exception) {
			throw new IllegalStateException("Unable to load RSA public key", exception);
		}
	}

	public static KeyPair generateForTests() {
		try {
			var generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return generator.generateKeyPair();
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("Unable to generate ephemeral RSA key pair", exception);
		}
	}

	private static byte[] decodePem(Path path, String label) throws IOException {
		String pem = Files.readString(path).replace("-----BEGIN " + label + "-----", "")
				.replace("-----END " + label + "-----", "").replaceAll("\\s", "");
		return Base64.getDecoder().decode(pem);
	}
}
