package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.time.BrazilClock;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Test-only factory of signed NF-e XML. Certificates and keys are generated on the fly with the
 * JDK's {@code keytool} into a temp keystore — nothing secret is ever committed. The certificates
 * mimic the ICP-Brasil e-CNPJ convention of carrying the holder's CNPJ as a {@code :<cnpj>}
 * suffix of the subject CN.
 */
final class SignedNfeXmlFixture {

    static final String ISSUER_CNPJ = "12345678000190";
    static final String CA_ALIAS = "ca";
    static final String ISSUER_ALIAS = "loja";
    static final String SELF_SIGNED_ISSUER_ALIAS = "loja-autoassinada";
    static final String OTHER_COMPANY_ALIAS = "outra";
    static final String WEAK_KEY_ALIAS = "fraca";

    private static final String STORE_PASSWORD = "changeit";
    private static final String C14N_10 = "http://www.w3.org/TR/2001/REC-xml-c14n-20010315";
    private static final String RSA_SHA256 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256";

    private final KeyStore keyStore;

    private SignedNfeXmlFixture(KeyStore keyStore) {
        this.keyStore = keyStore;
    }

    /** One test CA, an issuer certificate under it, a self-signed look-alike, another company and a weak key. */
    static SignedNfeXmlFixture generate(Path directory) throws Exception {
        var keyStorePath = directory.resolve("test-certificates.p12");
        keytool(keyStorePath, "-genkeypair", "-alias", CA_ALIAS, "-keysize", "2048",
                "-dname", "CN=AC Raiz de Teste, O=Teste", "-ext", "bc:c=ca:true", "-ext", "ku=keyCertSign,cRLSign",
                "-validity", "3650");
        keytool(keyStorePath, "-genkeypair", "-alias", ISSUER_ALIAS, "-keysize", "2048",
                "-dname", "CN=LOJA ONLINE TESTE LTDA:" + ISSUER_CNPJ + ", O=ICP-Brasil de Teste",
                "-signer", CA_ALIAS, "-ext", "ku=digitalSignature,nonRepudiation", "-validity", "365");
        keytool(keyStorePath, "-genkeypair", "-alias", SELF_SIGNED_ISSUER_ALIAS, "-keysize", "2048",
                "-dname", "CN=LOJA ONLINE TESTE LTDA:" + ISSUER_CNPJ + ", O=Qualquer Um", "-validity", "365");
        keytool(keyStorePath, "-genkeypair", "-alias", OTHER_COMPANY_ALIAS, "-keysize", "2048",
                "-dname", "CN=OUTRA EMPRESA LTDA:99887766000155, O=ICP-Brasil de Teste",
                "-signer", CA_ALIAS, "-validity", "365");
        keytool(keyStorePath, "-genkeypair", "-alias", WEAK_KEY_ALIAS, "-keysize", "1024",
                "-dname", "CN=LOJA ONLINE TESTE LTDA:" + ISSUER_CNPJ + ", O=ICP-Brasil de Teste",
                "-signer", CA_ALIAS, "-validity", "365");
        var keyStore = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keyStorePath)) {
            keyStore.load(input, STORE_PASSWORD.toCharArray());
        }
        return new SignedNfeXmlFixture(keyStore);
    }

    private static void keytool(Path keyStorePath, String... arguments) throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(arguments));
        command.addAll(List.of("-keyalg", "RSA", "-startdate", "-1d",
                "-keystore", keyStorePath.toString(), "-storetype", "PKCS12",
                "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("keytool failed: " + output);
        }
    }

    X509Certificate certificate(String alias) throws Exception {
        return (X509Certificate) keyStore.getCertificate(alias);
    }

    /** An online (indPres=2) model-55 NF-e issued now by {@link #ISSUER_CNPJ}, with one barcoded line. */
    static String unsignedOnlineNfe(String chave) {
        return unsignedNfe(chave, "2", OffsetDateTime.now(BrazilClock.ZONE));
    }

    static String unsignedNfe(String chave, String indPres, OffsetDateTime issuedAt) {
        var dhEmi = issuedAt.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        return """
                <?xml version="1.0" encoding="UTF-8"?>\
                <nfeProc xmlns="http://www.portalfiscal.inf.br/nfe" versao="4.00">\
                <NFe xmlns="http://www.portalfiscal.inf.br/nfe">\
                <infNFe Id="NFe%s" versao="4.00">\
                <ide><mod>55</mod><dhEmi>%s</dhEmi><indPres>%s</indPres></ide>\
                <emit><CNPJ>%s</CNPJ><xNome>LOJA ONLINE TESTE LTDA</xNome></emit>\
                <det nItem="1"><prod><cEAN>7891000100103</cEAN><xProd>LEITE EM PO 400G</xProd>\
                <uCom>UN</uCom><qCom>2.0000</qCom><vUnCom>15.9000</vUnCom><vProd>31.80</vProd></prod></det>\
                <total><ICMSTot><vProd>31.80</vProd><vDesc>0.00</vDesc><vNF>31.80</vNF></ICMSTot></total>\
                </infNFe></NFe>\
                <protNFe versao="4.00"><infProt><chNFe>%s</chNFe><cStat>100</cStat></infProt></protNFe>\
                </nfeProc>""".formatted(chave, dhEmi, indPres, ISSUER_CNPJ, chave);
    }

    /** A structurally valid model-55 chave for {@link #ISSUER_CNPJ} (correct mod-11 check digit). */
    static String chaveForIssuer() {
        var withoutCheckDigit = "35" + "2611" + ISSUER_CNPJ + "55" + "001" + "000001234" + "1" + "12345678";
        for (var checkDigit = 0; checkDigit <= 9; checkDigit++) {
            var candidate = withoutCheckDigit + checkDigit;
            if (ChaveAcessoParser.hasValidCheckDigit(candidate)) return candidate;
        }
        throw new IllegalStateException("no valid check digit");
    }

    /** Signs the way the NF-e layout mandates: enveloped signature over infNFe, C14N 1.0, RSA-SHA1. */
    String sign(String xml, String alias) throws Exception {
        return sign(xml, alias, SignatureMethod.RSA_SHA1, DigestMethod.SHA1, List.of(Transform.ENVELOPED, C14N_10));
    }

    String signWithSha256(String xml, String alias) throws Exception {
        return sign(xml, alias, RSA_SHA256, DigestMethod.SHA256, List.of(Transform.ENVELOPED, C14N_10));
    }

    String sign(String xml, String alias, String signatureMethod, String digestMethod,
                List<String> transformAlgorithms) throws Exception {
        var document = parse(xml);
        var infNfe = (Element) document.getElementsByTagNameNS("*", "infNFe").item(0);
        var signatureFactory = XMLSignatureFactory.getInstance("DOM");
        var transforms = new ArrayList<Transform>();
        for (var algorithm : transformAlgorithms) {
            transforms.add(signatureFactory.newTransform(algorithm, (TransformParameterSpec) null));
        }
        var reference = signatureFactory.newReference("#" + infNfe.getAttribute("Id"),
                signatureFactory.newDigestMethod(digestMethod, null), transforms, null, null);
        var signedInfo = signatureFactory.newSignedInfo(
                signatureFactory.newCanonicalizationMethod(CanonicalizationMethod.INCLUSIVE, (C14NMethodParameterSpec) null),
                signatureFactory.newSignatureMethod(signatureMethod, null),
                List.of(reference));
        var keyInfoFactory = signatureFactory.getKeyInfoFactory();
        var keyInfo = keyInfoFactory.newKeyInfo(List.of(keyInfoFactory.newX509Data(List.of(certificate(alias)))));
        var privateKey = (PrivateKey) keyStore.getKey(alias, STORE_PASSWORD.toCharArray());
        var signContext = new DOMSignContext(privateKey, infNfe.getParentNode());
        signContext.setIdAttributeNS(infNfe, null, "Id");
        signatureFactory.newXMLSignature(signedInfo, keyInfo).sign(signContext);
        return serialize(document);
    }

    private static Document parse(String xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static String serialize(Document document) throws Exception {
        var output = new StringWriter();
        TransformerFactory.newInstance().newTransformer().transform(new DOMSource(document), new StreamResult(output));
        return output.toString();
    }
}
