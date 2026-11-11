package com.relyon.economizaai.service.sefaz;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import static com.relyon.economizaai.service.sefaz.SignedNfeXmlFixture.CA_ALIAS;
import static com.relyon.economizaai.service.sefaz.SignedNfeXmlFixture.ISSUER_ALIAS;
import static com.relyon.economizaai.service.sefaz.SignedNfeXmlFixture.SELF_SIGNED_ISSUER_ALIAS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NfeTrustAnchorsTest {

    private static SignedNfeXmlFixture fixture;
    private static Path directory;

    @BeforeAll
    static void generateCertificates(@TempDir Path tempDirectory) throws Exception {
        directory = tempDirectory;
        fixture = SignedNfeXmlFixture.generate(tempDirectory);
    }

    private static String pem(X509Certificate certificate) throws Exception {
        var lineBreak = "\n".getBytes(StandardCharsets.US_ASCII);
        return "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, lineBreak).encodeToString(certificate.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
    }

    private NfeTrustAnchors loadedFrom(String bundlePath) {
        var trustAnchors = new NfeTrustAnchors(bundlePath);
        trustAnchors.loadBundle();
        return trustAnchors;
    }

    @Test
    void loadsRootsFromThePemBundleAndAnchorsCertificatesIssuedUnderThem() throws Exception {
        var bundle = directory.resolve("icp-brasil-test.pem");
        Files.writeString(bundle, pem(fixture.certificate(CA_ALIAS)));

        var trustAnchors = loadedFrom(bundle.toString());

        assertTrue(trustAnchors.isConfigured());
        assertTrue(trustAnchors.chainsToTrustAnchor(fixture.certificate(ISSUER_ALIAS), List.of(), LocalDateTime.now()));
        assertFalse(trustAnchors.chainsToTrustAnchor(
                fixture.certificate(SELF_SIGNED_ISSUER_ALIAS), List.of(), LocalDateTime.now()));
    }

    @Test
    void certificateOutsideItsValidityAtSigningTimeIsNotAnchored() throws Exception {
        var trustAnchors = NfeTrustAnchors.of(List.of(fixture.certificate(CA_ALIAS)));

        assertFalse(trustAnchors.chainsToTrustAnchor(
                fixture.certificate(ISSUER_ALIAS), List.of(), LocalDateTime.now().plusYears(3)));
    }

    @Test
    void withoutABundleNothingIsEverAnchored() throws Exception {
        var trustAnchors = loadedFrom("");

        assertFalse(trustAnchors.isConfigured());
        assertFalse(trustAnchors.chainsToTrustAnchor(fixture.certificate(ISSUER_ALIAS), List.of(), LocalDateTime.now()));
    }

    @Test
    void unreadableBundleFailsClosedInsteadOfBreakingStartup() {
        var trustAnchors = loadedFrom(directory.resolve("does-not-exist.pem").toString());

        assertFalse(trustAnchors.isConfigured());
    }

    @Test
    void bundleHoldingOnlyANonRootCertificateAnchorsNothing() throws Exception {
        var bundle = directory.resolve("leaf-only.pem");
        Files.writeString(bundle, pem(fixture.certificate(ISSUER_ALIAS)));

        assertFalse(loadedFrom(bundle.toString()).isConfigured());
    }
}
