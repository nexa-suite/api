package com.nexa.api.sales.infrastructure.seed;

import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;

public final class ClientAccountSeedValidator {
	public static final String EXPECTED_SHA256 = "a425306d16e4a6b3b956f93646fb90c8924113584c45f47e6e6c0a8c190ed8a5";
	private ClientAccountSeedValidator() { }
	public static void validate(List<ClientAccountSeedRecord> records, byte[] raw) {
		if (records == null || records.size() != 4) throw new IllegalStateException("client account seed count mismatch");
		if (!EXPECTED_SHA256.equals(sha256(raw))) throw new IllegalStateException("client account seed checksum mismatch");
		var codes = new HashSet<String>(); var rucs = new HashSet<String>();
		for (var record : records) {
			if (record == null || record.code() == null || !codes.add(record.code()) || record.ruc() == null || !rucs.add(record.ruc()) || !record.ruc().matches("\\d{11}")) throw new IllegalStateException("client account seed identity mismatch");
		}
	}
	private static String sha256(byte[] raw) { try { var digest=MessageDigest.getInstance("SHA-256").digest(raw); var result=new StringBuilder(); for(byte value:digest) result.append(String.format("%02x",value)); return result.toString(); } catch(Exception exception) { throw new IllegalStateException("SHA-256 unavailable",exception); } }
}
