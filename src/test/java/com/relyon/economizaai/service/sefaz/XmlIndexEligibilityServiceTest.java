package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.exception.PaidApiQuotaExceededException;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ReceiptChannel;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.repository.UserRepository;
import com.relyon.economizaai.service.sefaz.NfeXmlSignatureVerifier.Status;
import com.relyon.economizaai.service.sefaz.NfeXmlSignatureVerifier.Verdict;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.relyon.economizaai.service.sefaz.SignedNfeXmlFixture.CA_ALIAS;
import static com.relyon.economizaai.service.sefaz.SignedNfeXmlFixture.ISSUER_ALIAS;
import static com.relyon.economizaai.service.sefaz.SignedNfeXmlFixture.ISSUER_CNPJ;
import static com.relyon.economizaai.service.sefaz.SignedNfeXmlFixture.SELF_SIGNED_ISSUER_ALIAS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class XmlIndexEligibilityServiceTest {

    private static final String CHAVE = SignedNfeXmlFixture.chaveForIssuer();
    private static final LocalDateTime ISSUED_AT = LocalDateTime.of(2026, 11, 3, 14, 30);
    private static final UUID USER_ID = UUID.randomUUID();

    private static SignedNfeXmlFixture fixture;

    @Mock private NfeXmlSignatureVerifier signatureVerifier;
    @Mock private SefazIngestionService sefazIngestionService;
    @Mock private UserRepository userRepository;

    private final CollaborativeProperties properties = new CollaborativeProperties();
    private XmlIndexEligibilityService service;

    @BeforeAll
    static void generateCertificates(@TempDir Path directory) throws Exception {
        fixture = SignedNfeXmlFixture.generate(directory);
    }

    @BeforeEach
    void setUp() {
        service = new XmlIndexEligibilityService(signatureVerifier, sefazIngestionService, userRepository, properties);
        lenient().when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(User.builder().id(USER_ID).contributionOptIn(true).build()));
    }

    private static ParsedReceiptItem line(String ean, String unitPrice, String totalPrice) {
        return ParsedReceiptItem.builder().lineNumber(1).rawDescription("LEITE EM PO 400G").ean(ean)
                .quantity(new BigDecimal("2")).unit("UN")
                .unitPrice(new BigDecimal(unitPrice)).totalPrice(new BigDecimal(totalPrice)).build();
    }

    private static ParsedReceipt receipt(ReceiptChannel channel, String total, ParsedReceiptItem... items) {
        return ParsedReceipt.builder().chaveAcesso(CHAVE).cnpjEmitente(ISSUER_CNPJ)
                .issuedAt(ISSUED_AT).totalAmount(new BigDecimal(total)).channel(channel)
                .items(List.of(items)).build();
    }

    private static ParsedReceipt onlineReceipt() {
        return receipt(ReceiptChannel.ONLINE, "31.80", line("7891000100103", "15.90", "31.80"));
    }

    private void signatureIs(Status status, ParsedReceipt signedReceipt) {
        when(signatureVerifier.verify("<xml/>", CHAVE)).thenReturn(new Verdict(status, "test", signedReceipt));
    }

    private void reconsultReturns(ParsedReceipt reconsulted) {
        when(sefazIngestionService.fetchViaInfosimplesFallback(CHAVE, UnidadeFederativa.SP, USER_ID))
                .thenReturn(new SefazIngestionService.FetchedDocument(null, null, CHAVE, UnidadeFederativa.SP, null, reconsulted));
    }

    @Test
    void inStoreXmlNeverFeedsTheIndexAndCostsNothing() {
        var inStore = receipt(ReceiptChannel.IN_STORE, "31.80", line("7891000100103", "15.90", "31.80"));

        assertFalse(service.isIndexEligible("<xml/>", inStore, USER_ID));
        verifyNoInteractions(signatureVerifier, sefazIngestionService);
    }

    @Test
    void xmlWithoutChannelIsTreatedAsInStore() {
        var noChannel = receipt(null, "31.80", line("7891000100103", "15.90", "31.80"));

        assertFalse(service.isIndexEligible("<xml/>", noChannel, USER_ID));
        verifyNoInteractions(signatureVerifier, sefazIngestionService);
    }

    @Test
    void trustedSignatureCoveringTheParsedContentIsEligibleWithoutAPaidCall() {
        signatureIs(Status.TRUSTED, onlineReceipt());

        assertTrue(service.isIndexEligible("<xml/>", onlineReceipt(), USER_ID));
        verifyNoInteractions(sefazIngestionService);
    }

    @Test
    void trustedSignatureThatDoesNotCoverWhatWasParsedIsDeniedOutright() {
        // The parser saw a second, cheap line that is not in the signed content.
        var parsedWithSmuggledLine = receipt(ReceiptChannel.ONLINE, "31.80",
                line("7891000100103", "15.90", "31.80"), line("7891000100103", "0.01", "0.01"));
        signatureIs(Status.TRUSTED, onlineReceipt());

        assertFalse(service.isIndexEligible("<xml/>", parsedWithSmuggledLine, USER_ID));
        verifyNoInteractions(sefazIngestionService);
    }

    @Test
    void trustedSignatureWithADifferentSignedChannelIsDenied() {
        var signedAsInStore = receipt(ReceiptChannel.IN_STORE, "31.80", line("7891000100103", "15.90", "31.80"));
        signatureIs(Status.TRUSTED, signedAsInStore);

        assertFalse(service.isIndexEligible("<xml/>", onlineReceipt(), USER_ID));
    }

    @Test
    void unsignedXmlConfirmedByTheReconsultIsEligible() {
        signatureIs(Status.UNSIGNED, null);
        reconsultReturns(receipt(null, "31.80", line(null, "15.90", "31.80")));

        assertTrue(service.isIndexEligible("<xml/>", onlineReceipt(), USER_ID));
    }

    @Test
    void validSignatureWithoutAnAnchoredChainStillNeedsTheReconsult() {
        signatureIs(Status.UNTRUSTED_CHAIN, onlineReceipt());
        reconsultReturns(receipt(null, "31.80", line(null, "15.90", "31.80")));

        assertTrue(service.isIndexEligible("<xml/>", onlineReceipt(), USER_ID));
        verify(sefazIngestionService).fetchViaInfosimplesFallback(CHAVE, UnidadeFederativa.SP, USER_ID);
    }

    @Test
    void reconsultWithADifferentPriceDeniesTheXml() {
        signatureIs(Status.INVALID, null);
        reconsultReturns(receipt(null, "61.80", line(null, "30.90", "61.80")));

        assertFalse(service.isIndexEligible("<xml/>", onlineReceipt(), USER_ID));
    }

    @Test
    void reconsultWithADifferentIssuerOrItemCountDeniesTheXml() {
        signatureIs(Status.UNSIGNED, null);
        var otherIssuer = ParsedReceipt.builder().chaveAcesso(CHAVE).cnpjEmitente("99887766000155")
                .totalAmount(new BigDecimal("31.80")).items(List.of(line(null, "15.90", "31.80"))).build();
        reconsultReturns(otherIssuer);
        assertFalse(service.isIndexEligible("<xml/>", onlineReceipt(), USER_ID));

        var splitInTwoLines = receipt(null, "31.80", line(null, "15.90", "15.90"), line(null, "15.90", "15.90"));
        reconsultReturns(splitInTwoLines);
        assertFalse(service.isIndexEligible("<xml/>", onlineReceipt(), USER_ID));
    }

    @Test
    void reconsultBlockedByCapOrProviderFailureDeniesWithoutThrowing() {
        signatureIs(Status.UNSIGNED, null);
        when(sefazIngestionService.fetchViaInfosimplesFallback(any(), any(), any()))
                .thenThrow(new PaidApiQuotaExceededException("INFOSIMPLES"));

        assertFalse(service.isIndexEligible("<xml/>", onlineReceipt(), USER_ID));
    }

    @Test
    void noPaidReconsultWhenTheOwnerOptedOutOfContributing() {
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(User.builder().id(USER_ID).contributionOptIn(false).build()));

        assertFalse(service.isIndexEligible("<xml/>", onlineReceipt(), USER_ID));
        verifyNoInteractions(signatureVerifier, sefazIngestionService);
    }

    @Test
    void noPaidReconsultWhenNoLineHasABarcode() {
        var withoutEan = receipt(ReceiptChannel.ONLINE, "31.80", line(null, "15.90", "31.80"));

        assertFalse(service.isIndexEligible("<xml/>", withoutEan, USER_ID));
        verifyNoInteractions(signatureVerifier, sefazIngestionService);
    }

    @Test
    void noPaidReconsultWhenTheIndexIsSwitchedOff() {
        properties.getCollaborative().setEnabled(false);

        assertFalse(service.isIndexEligible("<xml/>", onlineReceipt(), USER_ID));
        verifyNoInteractions(sefazIngestionService);
    }

    @Test
    void paidReconsultCanBeSwitchedOff() {
        ReflectionTestUtils.setField(service, "paidReconsultEnabled", false);
        signatureIs(Status.UNSIGNED, null);

        assertFalse(service.isIndexEligible("<xml/>", onlineReceipt(), USER_ID));
        verify(sefazIngestionService, never()).fetchViaInfosimplesFallback(any(), any(), any());
    }

    @Test
    void unsignedNfceClaimingToBeOnlineIsNotReconsulted() {
        var nfceChave = CHAVE.substring(0, 20) + "65" + CHAVE.substring(22);
        var nfce = ParsedReceipt.builder().chaveAcesso(nfceChave).cnpjEmitente(ISSUER_CNPJ)
                .totalAmount(new BigDecimal("31.80")).channel(ReceiptChannel.ONLINE)
                .items(List.of(line("7891000100103", "15.90", "31.80"))).build();
        when(signatureVerifier.verify("<xml/>", nfceChave)).thenReturn(new Verdict(Status.UNSIGNED, "test", null));

        assertFalse(service.isIndexEligible("<xml/>", nfce, USER_ID));
        verifyNoInteractions(sefazIngestionService);
    }

    // ---- real signature + real parser, end to end ----

    private XmlIndexEligibilityService serviceWithRealVerifier() throws Exception {
        var verifier = new NfeXmlSignatureVerifier(NfeTrustAnchors.of(List.of(fixture.certificate(CA_ALIAS))));
        return new XmlIndexEligibilityService(verifier, sefazIngestionService, userRepository, properties);
    }

    @Test
    void realSignedOnlineXmlIsEligibleOnTheSignatureAlone() throws Exception {
        var signedXml = fixture.sign(SignedNfeXmlFixture.unsignedOnlineNfe(CHAVE), ISSUER_ALIAS);
        var parsed = NfceXmlParser.parse(CpfMasker.strip(signedXml), CHAVE, null);

        assertTrue(serviceWithRealVerifier().isIndexEligible(signedXml, parsed, USER_ID));
        verifyNoInteractions(sefazIngestionService);
    }

    @Test
    void realSignedXmlWithASmuggledCheapLineIsDenied() throws Exception {
        var forgedLine = "<det nItem=\"9\"><prod><cEAN>7891000100103</cEAN><xProd>LEITE EM PO 400G</xProd>"
                + "<uCom>UN</uCom><qCom>1.0000</qCom><vUnCom>0.0100</vUnCom><vProd>0.01</vProd></prod></det>";
        var wrappedXml = fixture.sign(SignedNfeXmlFixture.unsignedOnlineNfe(CHAVE), ISSUER_ALIAS)
                .replace("<protNFe", forgedLine + "<protNFe");
        var parsed = NfceXmlParser.parse(CpfMasker.strip(wrappedXml), CHAVE, null);

        assertFalse(serviceWithRealVerifier().isIndexEligible(wrappedXml, parsed, USER_ID));
        verifyNoInteractions(sefazIngestionService);
    }

    @Test
    void realSelfSignedForgeryFallsBackToTheReconsultAndIsDeniedWhenItDoesNotMatch() throws Exception {
        var forgedXml = fixture.sign(SignedNfeXmlFixture.unsignedOnlineNfe(CHAVE), SELF_SIGNED_ISSUER_ALIAS);
        var parsed = NfceXmlParser.parse(CpfMasker.strip(forgedXml), CHAVE, null);
        // SEFAZ knows this chave with other prices.
        reconsultReturns(receipt(null, "61.80", line(null, "30.90", "61.80")));

        assertFalse(serviceWithRealVerifier().isIndexEligible(forgedXml, parsed, USER_ID));
        verify(sefazIngestionService).fetchViaInfosimplesFallback(CHAVE, UnidadeFederativa.SP, USER_ID);
    }

    @Test
    void realTamperedXmlIsDeniedWhenTheReconsultIsUnavailable() throws Exception {
        var tamperedXml = fixture.sign(SignedNfeXmlFixture.unsignedOnlineNfe(CHAVE), ISSUER_ALIAS)
                .replace("<vUnCom>15.9000</vUnCom><vProd>31.80</vProd>", "<vUnCom>1.0000</vUnCom><vProd>2.00</vProd>");
        var parsed = NfceXmlParser.parse(CpfMasker.strip(tamperedXml), CHAVE, null);
        when(sefazIngestionService.fetchViaInfosimplesFallback(any(), any(), any()))
                .thenThrow(new IllegalStateException("provider down"));

        assertFalse(serviceWithRealVerifier().isIndexEligible(tamperedXml, parsed, USER_ID));
    }
}
