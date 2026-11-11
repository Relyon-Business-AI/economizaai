package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.time.BrazilClock;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The certificate authorities an NF-e signing certificate must chain to: the ICP-Brasil roots
 * (trust anchors) plus the intermediate ACs (path-building material), loaded from an operator
 * supplied PEM bundle. NF-e documents embed only the leaf certificate, so the intermediates
 * have to come from the bundle too.
 *
 * <p>Fail-closed: with no bundle configured — the default — nothing is anchored, a signature
 * can never be {@code TRUSTED}, and the caller has to establish authenticity another way.
 * Validation is offline: the path is checked for the moment the nota was issued (signing
 * certificates last one to three years and are usually expired by the time a nota is uploaded)
 * and revocation is NOT consulted.
 */
@Slf4j
@Component
public class NfeTrustAnchors {

    private final String bundlePath;
    private Set<TrustAnchor> anchors = Set.of();
    private List<X509Certificate> intermediates = List.of();

    public NfeTrustAnchors(@Value("${economizaai.xml-import.trust-bundle-path:}") String bundlePath) {
        this.bundlePath = bundlePath;
    }

    /** Test/programmatic construction from already-parsed certificates. */
    static NfeTrustAnchors of(List<X509Certificate> bundle) {
        var trustAnchors = new NfeTrustAnchors("");
        trustAnchors.index(bundle);
        return trustAnchors;
    }

    @PostConstruct
    void loadBundle() {
        if (bundlePath == null || bundlePath.isBlank()) {
            log.info("nfe_xml.trust_anchors.none — XML signatures are never treated as proof of origin");
            return;
        }
        try (var input = Files.newInputStream(Path.of(bundlePath))) {
            var certificates = CertificateFactory.getInstance("X.509").generateCertificates(input).stream()
                    .map(X509Certificate.class::cast)
                    .toList();
            index(certificates);
            log.info("nfe_xml.trust_anchors.loaded roots={} intermediates={}", anchors.size(), intermediates.size());
        } catch (IOException | CertificateException | RuntimeException ex) {
            log.error("nfe_xml.trust_anchors.load_failed path={} reason={} — continuing with none",
                    bundlePath, ex.getMessage());
        }
    }

    /** Self-signed certificates are the anchors; everything else only helps build the path. */
    private void index(List<X509Certificate> bundle) {
        var roots = new HashSet<TrustAnchor>();
        var chainCertificates = new ArrayList<X509Certificate>();
        for (var certificate : bundle) {
            if (isSelfSigned(certificate)) {
                roots.add(new TrustAnchor(certificate, null));
            } else {
                chainCertificates.add(certificate);
            }
        }
        anchors = Set.copyOf(roots);
        intermediates = List.copyOf(chainCertificates);
    }

    private static boolean isSelfSigned(X509Certificate certificate) {
        if (!certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())) return false;
        try {
            certificate.verify(certificate.getPublicKey());
            return true;
        } catch (GeneralSecurityException ex) {
            return false;
        }
    }

    public boolean isConfigured() {
        return !anchors.isEmpty();
    }

    /**
     * Whether {@code signingCertificate} builds a valid PKIX path to a configured anchor, with
     * every certificate of the path valid at {@code signedAt} (now when unknown).
     */
    public boolean chainsToTrustAnchor(X509Certificate signingCertificate, List<X509Certificate> embedded,
                                       LocalDateTime signedAt) {
        if (anchors.isEmpty() || signingCertificate == null) return false;
        try {
            var target = new X509CertSelector();
            target.setCertificate(signingCertificate);
            var parameters = new PKIXBuilderParameters(anchors, target);
            parameters.setRevocationEnabled(false);
            var validationMoment = signedAt != null ? signedAt : BrazilClock.nowDateTime();
            parameters.setDate(Date.from(validationMoment.atZone(BrazilClock.ZONE).toInstant()));
            var pathMaterial = new ArrayList<X509Certificate>(intermediates);
            pathMaterial.addAll(embedded);
            pathMaterial.add(signingCertificate);
            parameters.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(pathMaterial)));
            CertPathBuilder.getInstance("PKIX").build(parameters);
            return true;
        } catch (GeneralSecurityException ex) {
            log.debug("nfe_xml.trust_anchors.path_rejected reason={}", ex.getMessage());
            return false;
        }
    }
}
