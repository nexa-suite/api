package com.nexa.api.bootstrap.local.seed;

import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;

public final class ClientAccountSeedValidator {
	public static final String EXPECTED_SHA256 = "0d051dd6380eb041facd05534b31b858d1c3318981ed6657442b1f9bacd0dbdb";
	private ClientAccountSeedValidator() { }
	public static void validate(List<ClientAccountSeedRecord> records, byte[] raw) {
		if (records == null || records.size() != 4) throw new IllegalStateException("client account seed count mismatch");
		if (!EXPECTED_SHA256.equals(sha256(raw))) throw new IllegalStateException("client account seed checksum mismatch");
		var codes = new HashSet<String>(); var rucs = new HashSet<String>();
		for (var record : records) {
			if (record == null || record.code() == null || !codes.add(record.code()) || record.ruc() == null || !rucs.add(record.ruc()) || !record.ruc().matches("\\d{11}")) throw new IllegalStateException("client account seed identity mismatch");
			if (record.address() != null && !record.address().isBlank()
					&& (blank(record.departmentCode()) || blank(record.provinceCode()) || blank(record.districtCode()))) {
				throw new IllegalStateException("client account seed address geography is incomplete");
			}
		}
	}
	private static boolean blank(String value) { return value == null || value.isBlank(); }
	private static String sha256(byte[] raw) { try { var digest=MessageDigest.getInstance("SHA-256").digest(raw); var result=new StringBuilder(); for(byte value:digest) result.append(String.format("%02x",value)); return result.toString(); } catch(Exception exception) { throw new IllegalStateException("SHA-256 unavailable",exception); } }
}
