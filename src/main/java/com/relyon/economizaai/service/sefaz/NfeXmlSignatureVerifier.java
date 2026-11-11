package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.xml.XMLConstants;
import javax.xml.crypto.AlgorithmMethod;
import javax.xml.crypto.KeySelector;
import javax.xml.crypto.KeySelectorException;
import javax.xml.crypto.KeySelectorResult;
import javax.xml.crypto.MarshalException;
import javax.xml.crypto.XMLCryptoContext;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureException;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.X509Data;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies the issuer's XMLDSig signature on an uploaded NF-e/NFC-e XML and hands back the
 * SIGNED content, so the caller can compare it with what was actually parsed and persisted.
 *
 * <p>What "valid" means here, in order:
 * <ol>
 *   <li>the document parses with a hardened parser (no DOCTYPE, no external entities, no XInclude);</li>
 *   <li>it holds exactly one {@code infNFe}, whose {@code Id} is {@code NFe<chave>}, and that Id
 *       is unique in the document;</li>
 *   <li>the enveloped signature next to it follows the NF-e signature profile — one same-document
 *       reference to that {@code infNFe}, only the enveloped/C14N transforms, RSA with SHA-1 or
 *       SHA-256, RSA key of at least 2048 bits, no embedded objects;</li>
 *   <li>the signature and the reference digest validate against the certificate embedded in
 *       {@code KeyInfo};</li>
 *   <li>that certificate carries the same CNPJ root as the signed {@code emit/CNPJ} and as the
 *       chave itself;</li>
 *   <li>the certificate chains to a configured ICP-Brasil trust anchor ({@link NfeTrustAnchors}).</li>
 * </ol>
 * Steps 1–5 alone give {@link Status#UNTRUSTED_CHAIN}: anyone can mint a self-signed certificate
 * with a CNPJ in its subject, so that result proves integrity, not authenticity, and must not be
 * treated as proof of origin. Only {@link Status#TRUSTED} does.
 *
 * <p>The profile is enforced explicitly instead of through the JDK's "secure validation" switch
 * because that switch rejects {@code rsa-sha1}, which the NF-e layout mandates — with it on,
 * no real nota would ever validate. Everything else that switch guards against (external
 * reference URIs, XSLT/XPath transforms, duplicate Ids, weak keys) is refused here by allowlist.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NfeXmlSignatureVerifier {

    public enum Status {
        /** Signature valid, CNPJ bound, certificate chains to a configured ICP-Brasil anchor. */
        TRUSTED,
        /** Signature valid and CNPJ bound, but the certificate chain could not be anchored. */
        UNTRUSTED_CHAIN,
        /** No signature next to the {@code infNFe}. */
        UNSIGNED,
        /** Malformed document, signature outside the NF-e profile, or signature that does not validate. */
        INVALID
    }

    /**
     * @param reason        short machine reason for the log line
     * @param signedReceipt the receipt as parsed from the SIGNED {@code infNFe} only; present for
     *                      {@link Status#TRUSTED} and {@link Status#UNTRUSTED_CHAIN}
     */
    public record Verdict(Status status, String reason, ParsedReceipt signedReceipt) {
        static Verdict of(Status status, String reason) {
            return new Verdict(status, reason, null);
        }
    }

    private static final String NFE_NAMESPACE = "http://www.portalfiscal.inf.br/nfe";
    private static final String C14N_10 = "http://www.w3.org/TR/2001/REC-xml-c14n-20010315";
    private static final String RSA_SHA256 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256";
    private static final Set<String> ALLOWED_SIGNATURE_METHODS = Set.of(SignatureMethod.RSA_SHA1, RSA_SHA256);
    private static final Set<String> ALLOWED_DIGEST_METHODS = Set.of(DigestMethod.SHA1, DigestMethod.SHA256);
    private static final Set<String> ALLOWED_TRANSFORMS = Set.of(Transform.ENVELOPED, C14N_10);
    private static final int MIN_RSA_KEY_BITS = 2048;
    /** DER encoding of OID 2.16.76.1.3.3 — the ICP-Brasil "CNPJ of the certificate holder" otherName. */
    private static final byte[] ICP_BRASIL_CNPJ_OID = {0x06, 0x05, 0x60, 0x4C, 0x01, 0x03, 0x03};
    private static final Pattern FOURTEEN_DIGITS = Pattern.compile("\\d{14}");
    private static final Pattern CN_CNPJ_SUFFIX = Pattern.compile(":(\\d{14})\\s*$");
    private static final int OTHER_NAME = 0;

    private final NfeTrustAnchors trustAnchors;

    public Verdict verify(String xml, String chave) {
        if (xml == null || xml.isBlank() || chave == null) {
            return Verdict.of(Status.INVALID, "empty_document");
        }
        Document document;
        try {
            document = parseHardened(xml);
        } catch (ParserConfigurationException | SAXException | IOException ex) {
            log.debug("nfe_xml.signature.unparseable chave={} reason={}", LogMasker.chave(chave), ex.getMessage());
            return Verdict.of(Status.INVALID, "unparseable_document");
        }
        var infNfe = singleInfNfe(document);
        if (infNfe == null) {
            return Verdict.of(Status.INVALID, "infnfe_not_single");
        }
        var signedId = infNfe.getAttribute("Id");
        if (!("NFe" + chave).equals(signedId) || !isUniqueId(document, signedId)) {
            return Verdict.of(Status.INVALID, "infnfe_id_mismatch");
        }
        var signatureElement = siblingSignature(infNfe);
        if (signatureElement == null) {
            return Verdict.of(Status.UNSIGNED, "no_signature");
        }
        return verifySignature(signatureElement, infNfe, signedId, chave);
    }

    private Verdict verifySignature(Element signatureElement, Element infNfe, String signedId, String chave) {
        var certificateSelector = new EmbeddedCertificateSelector();
        var context = new DOMValidateContext(certificateSelector, signatureElement);
        // Profile enforced by allowlist below; the JDK switch would reject the mandatory rsa-sha1.
        context.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.FALSE);
        context.setIdAttributeNS(infNfe, null, "Id");
        try {
            var signature = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(context);
            var profileViolation = profileViolation(signature, signedId);
            if (profileViolation != null) {
                return Verdict.of(Status.INVALID, profileViolation);
            }
            if (!signature.validate(context)) {
                return Verdict.of(Status.INVALID, "signature_mismatch");
            }
        } catch (MarshalException | XMLSignatureException | RuntimeException ex) {
            log.debug("nfe_xml.signature.failed chave={} reason={}", LogMasker.chave(chave), ex.getMessage());
            return Verdict.of(Status.INVALID, "signature_unverifiable");
        }
        var certificate = certificateSelector.selected();
        var signedReceipt = parseSignedContent(infNfe, chave);
        if (signedReceipt == null) {
            return Verdict.of(Status.INVALID, "signed_content_unreadable");
        }
        if (!certificateBelongsToIssuer(certificate, signedReceipt.cnpjEmitente(), chave)) {
            return Verdict.of(Status.INVALID, "certificate_cnpj_mismatch");
        }
        var anchored = trustAnchors.chainsToTrustAnchor(
                certificate, certificateSelector.embedded(), signedReceipt.issuedAt());
        return anchored
                ? new Verdict(Status.TRUSTED, "issuer_signature", signedReceipt)
                : new Verdict(Status.UNTRUSTED_CHAIN, "chain_not_anchored", signedReceipt);
    }

    /** Null when the signature follows the NF-e profile, else the reason it doesn't. */
    private static String profileViolation(XMLSignature signature, String signedId) {
        var signedInfo = signature.getSignedInfo();
        if (!C14N_10.equals(signedInfo.getCanonicalizationMethod().getAlgorithm())) {
            return "canonicalization_not_allowed";
        }
        if (!ALLOWED_SIGNATURE_METHODS.contains(signedInfo.getSignatureMethod().getAlgorithm())) {
            return "signature_method_not_allowed";
        }
        if (!signature.getObjects().isEmpty()) {
            return "embedded_objects_not_allowed";
        }
        var references = signedInfo.getReferences();
        if (references.size() != 1) {
            return "reference_count";
        }
        var reference = (Reference) references.get(0);
        if (!("#" + signedId).equals(reference.getURI())) {
            return "reference_not_infnfe";
        }
        if (!ALLOWED_DIGEST_METHODS.contains(reference.getDigestMethod().getAlgorithm())) {
            return "digest_method_not_allowed";
        }
        var transforms = reference.getTransforms();
        var envelopedDeclared = false;
        for (var transform : transforms) {
            var algorithm = ((Transform) transform).getAlgorithm();
            if (!ALLOWED_TRANSFORMS.contains(algorithm)) {
                return "transform_not_allowed";
            }
            envelopedDeclared |= Transform.ENVELOPED.equals(algorithm);
        }
        return envelopedDeclared && transforms.size() <= 2 ? null : "transforms_outside_profile";
    }

    /**
     * Re-parses ONLY the signed subtree with the same parser the ingestion uses, so the caller
     * compares like with like. Anything a forger added outside the signed {@code infNFe} is
     * invisible here — and therefore shows up as a difference.
     */
    private static ParsedReceipt parseSignedContent(Element infNfe, String chave) {
        try {
            var transformerFactory = TransformerFactory.newInstance();
            transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            var transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            var serialized = new StringWriter();
            transformer.transform(new DOMSource(infNfe), new StreamResult(serialized));
            return NfceXmlParser.parse(CpfMasker.strip(serialized.toString()), chave, null);
        } catch (TransformerException | RuntimeException ex) {
            log.debug("nfe_xml.signature.signed_content_unreadable chave={} reason={}",
                    LogMasker.chave(chave), ex.getMessage());
            return null;
        }
    }

    private static Document parseHardened(String xml)
            throws ParserConfigurationException, SAXException, IOException {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(null);
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private static Element singleInfNfe(Document document) {
        var candidates = document.getElementsByTagNameNS("*", "infNFe");
        if (candidates.getLength() != 1) return null;
        var infNfe = (Element) candidates.item(0);
        return NFE_NAMESPACE.equals(infNfe.getNamespaceURI()) ? infNfe : null;
    }

    /** No other element may carry the signed Id — the reference must have a single possible target. */
    private static boolean isUniqueId(Document document, String signedId) {
        var allElements = document.getElementsByTagName("*");
        var holders = 0;
        for (var index = 0; index < allElements.getLength(); index++) {
            var attributes = allElements.item(index).getAttributes();
            for (var attributeIndex = 0; attributeIndex < attributes.getLength(); attributeIndex++) {
                var attribute = attributes.item(attributeIndex);
                if ("id".equalsIgnoreCase(attribute.getLocalName()) && signedId.equals(attribute.getNodeValue())) {
                    holders++;
                }
            }
        }
        return holders == 1;
    }

    /** The enveloped signature of an NF-e sits beside {@code infNFe}, as a child of {@code NFe}. */
    private static Element siblingSignature(Element infNfe) {
        for (var sibling = infNfe.getParentNode().getFirstChild(); sibling != null; sibling = sibling.getNextSibling()) {
            if (sibling.getNodeType() == Node.ELEMENT_NODE
                    && "Signature".equals(sibling.getLocalName())
                    && XMLSignature.XMLNS.equals(sibling.getNamespaceURI())) {
                return (Element) sibling;
            }
        }
        return null;
    }

    /**
     * The e-CNPJ certificate must belong to the company that issued the nota: same CNPJ ROOT
     * (the head office's certificate signs for every branch) as the signed emitente and as the
     * CNPJ embedded in the chave.
     */
    private static boolean certificateBelongsToIssuer(X509Certificate certificate, String signedCnpjEmitente,
                                                      String chave) {
        var certificateCnpj = cnpjOf(certificate).orElse(null);
        if (certificateCnpj == null || signedCnpjEmitente == null || signedCnpjEmitente.length() != 14) {
            return false;
        }
        var certificateRoot = certificateCnpj.substring(0, 8);
        var chaveCnpj = ChaveAcessoParser.extractCnpj(chave);
        return certificateRoot.equals(signedCnpjEmitente.substring(0, 8))
                && chaveCnpj != null && chaveCnpj.startsWith(certificateRoot);
    }

    /**
     * CNPJ of an ICP-Brasil e-CNPJ certificate: the otherName OID 2.16.76.1.3.3 in the subject
     * alternative names when present, else the {@code :<14 digits>} suffix of the subject CN.
     */
    static Optional<String> cnpjOf(X509Certificate certificate) {
        return cnpjFromSubjectAlternativeNames(certificate).or(() -> cnpjFromCommonName(certificate));
    }

    private static Optional<String> cnpjFromSubjectAlternativeNames(X509Certificate certificate) {
        Collection<List<?>> alternativeNames;
        try {
            alternativeNames = certificate.getSubjectAlternativeNames();
        } catch (CertificateParsingException ex) {
            return Optional.empty();
        }
        if (alternativeNames == null) return Optional.empty();
        return alternativeNames.stream()
                .filter(name -> name.size() >= 2 && Integer.valueOf(OTHER_NAME).equals(name.get(0))
                        && name.get(1) instanceof byte[])
                .map(name -> cnpjFromOtherName((byte[]) name.get(1)))
                .flatMap(Optional::stream)
                .findFirst();
    }

    /** The 14 digits that follow the CNPJ OID inside a DER-encoded otherName, if this is that otherName. */
    static Optional<String> cnpjFromOtherName(byte[] derOtherName) {
        var oidPosition = indexOf(derOtherName, ICP_BRASIL_CNPJ_OID);
        if (oidPosition < 0) return Optional.empty();
        var valueStart = oidPosition + ICP_BRASIL_CNPJ_OID.length;
        var value = new String(derOtherName, valueStart, derOtherName.length - valueStart, StandardCharsets.ISO_8859_1);
        var digits = FOURTEEN_DIGITS.matcher(value);
        return digits.find() ? Optional.of(digits.group()) : Optional.empty();
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        for (var start = 0; start <= haystack.length - needle.length; start++) {
            var matches = true;
            for (var offset = 0; offset < needle.length && matches; offset++) {
                matches = haystack[start + offset] == needle[offset];
            }
            if (matches) return start;
        }
        return -1;
    }

    private static Optional<String> cnpjFromCommonName(X509Certificate certificate) {
        try {
            return new LdapName(certificate.getSubjectX500Principal().getName()).getRdns().stream()
                    .filter(rdn -> "CN".equalsIgnoreCase(rdn.getType()))
                    .map(rdn -> CN_CNPJ_SUFFIX.matcher(String.valueOf(rdn.getValue())))
                    .filter(Matcher::find)
                    .map(matcher -> matcher.group(1))
                    .findFirst();
        } catch (InvalidNameException ex) {
            return Optional.empty();
        }
    }

    /**
     * Uses only the X.509 certificate embedded in {@code KeyInfo} — never a RetrievalMethod or a
     * bare key value — and refuses RSA keys below the ICP-Brasil minimum.
     */
    private static final class EmbeddedCertificateSelector extends KeySelector {
        private X509Certificate selected;
        private final List<X509Certificate> embedded = new ArrayList<>();

        @Override
        public KeySelectorResult select(KeyInfo keyInfo, Purpose purpose, AlgorithmMethod method,
                                        XMLCryptoContext context) throws KeySelectorException {
            if (keyInfo == null) throw new KeySelectorException("KeyInfo ausente");
            for (var keyInfoEntry : keyInfo.getContent()) {
                if (!(keyInfoEntry instanceof X509Data x509Data)) continue;
                for (var x509Entry : x509Data.getContent()) {
                    if (x509Entry instanceof X509Certificate certificate) embedded.add(certificate);
                }
            }
            if (embedded.isEmpty()) throw new KeySelectorException("X509Certificate ausente");
            selected = embedded.get(0);
            var publicKey = selected.getPublicKey();
            if (!(publicKey instanceof RSAPublicKey rsaKey) || rsaKey.getModulus().bitLength() < MIN_RSA_KEY_BITS) {
                throw new KeySelectorException("chave do certificado fora do perfil");
            }
            return () -> publicKey;
        }

        private X509Certificate selected() { return selected; }

        private List<X509Certificate> embedded() { return embedded; }
    }
}
