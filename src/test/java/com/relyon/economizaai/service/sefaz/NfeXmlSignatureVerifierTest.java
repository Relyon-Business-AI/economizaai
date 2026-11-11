package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.model.enums.ReceiptChannel;
import com.relyon.economizaai.service.sefaz.NfeXmlSignatureVerifier.Status;
import com.relyon.economizaai.time.BrazilClock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.Transform;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import static com.relyon.economizaai.service.sefaz.SignedNfeXmlFixture.CA_ALIAS;
import static com.relyon.economizaai.service.sefaz.SignedNfeXmlFixture.ISSUER_ALIAS;
import static com.relyon.economizaai.service.sefaz.SignedNfeXmlFixture.ISSUER_CNPJ;
import static com.relyon.economizaai.service.sefaz.SignedNfeXmlFixture.OTHER_COMPANY_ALIAS;
import static com.relyon.economizaai.service.sefaz.SignedNfeXmlFixture.SELF_SIGNED_ISSUER_ALIAS;
import static com.relyon.economizaai.service.sefaz.SignedNfeXmlFixture.WEAK_KEY_ALIAS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NfeXmlSignatureVerifierTest {

    private static final String CHAVE = SignedNfeXmlFixture.chaveForIssuer();

    private static SignedNfeXmlFixture fixture;
    private static NfeXmlSignatureVerifier verifierTrustingTestCa;
    private static NfeXmlSignatureVerifier verifierWithoutAnchors;

    @BeforeAll
    static void generateCertificates(@TempDir Path directory) throws Exception {
        fixture = SignedNfeXmlFixture.generate(directory);
        verifierTrustingTestCa = new NfeXmlSignatureVerifier(
                NfeTrustAnchors.of(List.of(fixture.certificate(CA_ALIAS))));
        verifierWithoutAnchors = new NfeXmlSignatureVerifier(NfeTrustAnchors.of(List.of()));
    }

    private static String signedByIssuer() throws Exception {
        return fixture.sign(SignedNfeXmlFixture.unsignedOnlineNfe(CHAVE), ISSUER_ALIAS);
    }

    @Test
    void issuerSignatureAnchoredInTheTrustBundleIsTrusted() throws Exception {
        var verdict = verifierTrustingTestCa.verify(signedByIssuer(), CHAVE);

        assertEquals(Status.TRUSTED, verdict.status(), verdict.reason());
        var signed = verdict.signedReceipt();
        assertEquals(ISSUER_CNPJ, signed.cnpjEmitente());
        assertEquals(ReceiptChannel.ONLINE, signed.channel());
        assertEquals(0, new BigDecimal("31.80").compareTo(signed.totalAmount()));
        assertEquals(1, signed.items().size());
        assertEquals("7891000100103", signed.items().get(0).ean());
    }

    @Test
    void sha256SignatureIsAlsoAccepted() throws Exception {
        var xml = fixture.signWithSha256(SignedNfeXmlFixture.unsignedOnlineNfe(CHAVE), ISSUER_ALIAS);

        assertEquals(Status.TRUSTED, verifierTrustingTestCa.verify(xml, CHAVE).status());
    }

    @Test
    void validSignatureWithoutAConfiguredAnchorIsNotProofOfOrigin() throws Exception {
        var verdict = verifierWithoutAnchors.verify(signedByIssuer(), CHAVE);

        assertEquals(Status.UNTRUSTED_CHAIN, verdict.status());
        assertNotNull(verdict.signedReceipt());
    }

    @Test
    void selfSignedCertificateCarryingTheIssuerCnpjIsNeverTrusted() throws Exception {
        var forged = fixture.sign(SignedNfeXmlFixture.unsignedOnlineNfe(CHAVE), SELF_SIGNED_ISSUER_ALIAS);

        assertEquals(Status.UNTRUSTED_CHAIN, verifierTrustingTestCa.verify(forged, CHAVE).status(),
                "the signature is mathematically valid, but anyone can mint this certificate");
    }

    @Test
    void certificateThatWasNotValidWhenTheNotaWasIssuedIsNotAnchored() throws Exception {
        var issuedYearsBeforeTheCertificate = SignedNfeXmlFixture.unsignedNfe(
                CHAVE, "2", OffsetDateTime.now(BrazilClock.ZONE).minusYears(5));

        var verdict = verifierTrustingTestCa.verify(fixture.sign(issuedYearsBeforeTheCertificate, ISSUER_ALIAS), CHAVE);

        assertEquals(Status.UNTRUSTED_CHAIN, verdict.status());
    }

    @Test
    void documentWithoutSignatureIsUnsigned() {
        var verdict = verifierTrustingTestCa.verify(SignedNfeXmlFixture.unsignedOnlineNfe(CHAVE), CHAVE);

        assertEquals(Status.UNSIGNED, verdict.status());
        assertNull(verdict.signedReceipt());
    }

    @Test
    void priceChangedAfterSigningInvalidatesTheSignature() throws Exception {
        var tampered = signedByIssuer()
                .replace("<vUnCom>15.9000</vUnCom><vProd>31.80</vProd>", "<vUnCom>1.0000</vUnCom><vProd>2.00</vProd>");

        var verdict = verifierTrustingTestCa.verify(tampered, CHAVE);

        assertEquals(Status.INVALID, verdict.status());
        assertEquals("signature_mismatch", verdict.reason());
    }

    @Test
    void tamperedSignatureValueIsInvalid() throws Exception {
        var signed = signedByIssuer();
        var valueStart = signed.indexOf("<SignatureValue>") + "<SignatureValue>".length();
        var flippedCharacter = signed.charAt(valueStart) == 'A' ? 'B' : 'A';
        var tampered = signed.substring(0, valueStart) + flippedCharacter + signed.substring(valueStart + 1);

        assertEquals(Status.INVALID, verifierTrustingTestCa.verify(tampered, CHAVE).status());
    }

    @Test
    void contentSmuggledOutsideTheSignedSubtreeIsNotPartOfTheSignedReceipt() throws Exception {
        var forgedLine = "<det nItem=\"9\"><prod><cEAN>7891000100103</cEAN><xProd>LEITE EM PO 400G</xProd>"
                + "<uCom>UN</uCom><qCom>1.0000</qCom><vUnCom>0.0100</vUnCom><vProd>0.01</vProd></prod></det>";
        var wrapped = signedByIssuer().replace("<protNFe", forgedLine + "<protNFe");

        var verdict = verifierTrustingTestCa.verify(wrapped, CHAVE);
        var wholeDocument = NfceXmlParser.parse(wrapped, CHAVE, null);

        assertEquals(Status.TRUSTED, verdict.status(), "the signed infNFe itself is untouched");
        assertEquals(2, wholeDocument.items().size(), "the ingestion parser does see the smuggled line");
        assertEquals(1, verdict.signedReceipt().items().size(), "the signed receipt does not");
    }

    @Test
    void certificateOfAnotherCompanyIsRejected() throws Exception {
        var signedByOtherCompany = fixture.sign(SignedNfeXmlFixture.unsignedOnlineNfe(CHAVE), OTHER_COMPANY_ALIAS);

        var verdict = verifierTrustingTestCa.verify(signedByOtherCompany, CHAVE);

        assertEquals(Status.INVALID, verdict.status());
        assertEquals("certificate_cnpj_mismatch", verdict.reason());
    }

    @Test
    void weakSigningKeyIsRejected() throws Exception {
        var weaklySigned = fixture.sign(SignedNfeXmlFixture.unsignedOnlineNfe(CHAVE), WEAK_KEY_ALIAS);

        assertEquals(Status.INVALID, verifierTrustingTestCa.verify(weaklySigned, CHAVE).status());
    }

    @Test
    void signedDocumentOfAnotherChaveIsRejected() throws Exception {
        var otherChave = CHAVE.substring(0, 43) + (CHAVE.charAt(43) == '0' ? '1' : '0');

        var verdict = verifierTrustingTestCa.verify(signedByIssuer(), otherChave);

        assertEquals(Status.INVALID, verdict.status());
        assertEquals("infnfe_id_mismatch", verdict.reason());
    }

    @Test
    void secondElementReusingTheSignedIdIsRejected() throws Exception {
        var duplicatedId = signedByIssuer().replace("<protNFe", "<extra Id=\"NFe" + CHAVE + "\"/><protNFe");

        var verdict = verifierTrustingTestCa.verify(duplicatedId, CHAVE);

        assertEquals(Status.INVALID, verdict.status());
        assertEquals("infnfe_id_mismatch", verdict.reason());
    }

    @Test
    void signatureThatDoesNotDeclareTheEnvelopedTransformIsOutsideTheProfile() throws Exception {
        var offProfile = fixture.sign(SignedNfeXmlFixture.unsignedOnlineNfe(CHAVE), ISSUER_ALIAS,
                SignatureMethod.RSA_SHA1, DigestMethod.SHA1, List.of("http://www.w3.org/TR/2001/REC-xml-c14n-20010315"));

        var verdict = verifierTrustingTestCa.verify(offProfile, CHAVE);

        assertEquals(Status.INVALID, verdict.status());
        assertEquals("transforms_outside_profile", verdict.reason());
    }

    @Test
    void transformOutsideTheAllowlistIsRejected() throws Exception {
        var offProfile = fixture.sign(SignedNfeXmlFixture.unsignedOnlineNfe(CHAVE), ISSUER_ALIAS,
                SignatureMethod.RSA_SHA1, DigestMethod.SHA1, List.of(Transform.ENVELOPED, CanonicalizationMethod.EXCLUSIVE));

        var verdict = verifierTrustingTestCa.verify(offProfile, CHAVE);

        assertEquals(Status.INVALID, verdict.status());
        assertEquals("transform_not_allowed", verdict.reason());
    }

    @Test
    void doctypeAndExternalEntitiesAreRefused() {
        var withDoctype = "<?xml version=\"1.0\"?><!DOCTYPE nfeProc [<!ENTITY leak SYSTEM \"file:///etc/hostname\">]>"
                + SignedNfeXmlFixture.unsignedOnlineNfe(CHAVE).substring(
                        SignedNfeXmlFixture.unsignedOnlineNfe(CHAVE).indexOf("<nfeProc"))
                        .replace("LOJA ONLINE TESTE LTDA", "&leak;");

        var verdict = verifierTrustingTestCa.verify(withDoctype, CHAVE);

        assertEquals(Status.INVALID, verdict.status());
        assertEquals("unparseable_document", verdict.reason());
    }

    @Test
    void garbageOrEmptyInputIsInvalid() {
        assertEquals(Status.INVALID, verifierTrustingTestCa.verify("not xml at all", CHAVE).status());
        assertEquals(Status.INVALID, verifierTrustingTestCa.verify("", CHAVE).status());
        assertEquals(Status.INVALID, verifierTrustingTestCa.verify(null, CHAVE).status());
    }

    @Test
    void cnpjIsReadFromTheCommonNameSuffix() throws Exception {
        assertEquals(ISSUER_CNPJ, NfeXmlSignatureVerifier.cnpjOf(fixture.certificate(ISSUER_ALIAS)).orElseThrow());
        assertTrue(NfeXmlSignatureVerifier.cnpjOf(fixture.certificate(CA_ALIAS)).isEmpty());
    }

    @Test
    void cnpjIsReadFromTheIcpBrasilOtherName() {
        // otherName ::= SEQUENCE { type-id OID 2.16.76.1.3.3, value [0] EXPLICIT OCTET STRING "<cnpj>" }
        var cnpjBytes = ISSUER_CNPJ.getBytes(StandardCharsets.US_ASCII);
        var encoded = new byte[7 + 4 + cnpjBytes.length];
        System.arraycopy(new byte[] {0x06, 0x05, 0x60, 0x4C, 0x01, 0x03, 0x03}, 0, encoded, 0, 7);
        System.arraycopy(new byte[] {(byte) 0xA0, 0x10, 0x04, 0x0E}, 0, encoded, 7, 4);
        System.arraycopy(cnpjBytes, 0, encoded, 11, cnpjBytes.length);
        var responsibleCpfOtherName = new byte[] {0x06, 0x05, 0x60, 0x4C, 0x01, 0x03, 0x02, (byte) 0xA0, 0x02, 0x04, 0x00};

        assertEquals(ISSUER_CNPJ, NfeXmlSignatureVerifier.cnpjFromOtherName(encoded).orElseThrow());
        assertTrue(NfeXmlSignatureVerifier.cnpjFromOtherName(responsibleCpfOtherName).isEmpty());
    }
}
