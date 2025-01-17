package net.woggioni.gbcs.server.test.utils;

import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

public class CertificateUtils {

    public record X509Credentials(
            KeyPair keyPair,
            X509Certificate certificate
    ){ }
    public static class CertificateAuthority {
        private final PrivateKey privateKey;
        private final X509Certificate certificate;

        public CertificateAuthority(PrivateKey privateKey, X509Certificate certificate) {
            this.privateKey = privateKey;
            this.certificate = certificate;
        }

        public PrivateKey getPrivateKey() { return privateKey; }
        public X509Certificate getCertificate() { return certificate; }
    }

    /**
     * Creates a new Certificate Authority (CA)
     * @param commonName The CA's common name
     * @param validityDays How long the CA should be valid for
     * @return The generated CA containing both private key and certificate
     */
    public static X509Credentials createCertificateAuthority(String commonName, int validityDays)
            throws Exception {
        // Generate key pair
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(4096);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();

        // Prepare certificate data
        X500Name issuerName = new X500Name("CN=" + commonName);
        BigInteger serialNumber = new BigInteger(160, new SecureRandom());
        Instant now = Instant.now();
        Date startDate = Date.from(now);
        Date endDate = Date.from(now.plus(validityDays, ChronoUnit.DAYS));

        // Create certificate builder
        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                issuerName,
                serialNumber,
                startDate,
                endDate,
                issuerName,
                keyPair.getPublic()
        );

        // Add CA extensions
        certBuilder.addExtension(
                Extension.basicConstraints,
                true,
                new BasicConstraints(true)
        );
        certBuilder.addExtension(
                Extension.keyUsage,
                true,
                new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign)
        );

        // Sign the certificate
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .build(keyPair.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter()
                .getCertificate(certBuilder.build(signer));

        return new X509Credentials(keyPair, cert);
    }

    /**
     * Creates a server certificate signed by the CA
     * @param ca The Certificate Authority to sign with
     * @param subjectName The server's common name
     * @param validityDays How long the certificate should be valid for
     * @return KeyPair containing the server's private key and certificate
     */
    public static X509Credentials createServerCertificate(X509Credentials ca, X500Name subjectName, int validityDays)
            throws Exception {
        // Generate server key pair
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        KeyPair serverKeyPair = keyPairGenerator.generateKeyPair();

        // Prepare certificate data
        X500Name issuerName = new X500Name(ca.certificate().getSubjectX500Principal().getName());
        BigInteger serialNumber = new BigInteger(160, new SecureRandom());
        Instant now = Instant.now();
        Date startDate = Date.from(now);
        Date endDate = Date.from(now.plus(validityDays, ChronoUnit.DAYS));

        // Create certificate builder
        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                issuerName,
                serialNumber,
                startDate,
                endDate,
                subjectName,
                serverKeyPair.getPublic()
        );

        // Add server certificate extensions
        certBuilder.addExtension(
                Extension.basicConstraints,
                true,
                new BasicConstraints(false)
        );
        certBuilder.addExtension(
                Extension.keyUsage,
                true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment)
        );
        certBuilder.addExtension(
                Extension.extendedKeyUsage,
                true,
                new ExtendedKeyUsage(new KeyPurposeId[]{KeyPurposeId.id_kp_serverAuth})
        );
        GeneralNames subjectAltNames = GeneralNames.getInstance(
            new DERSequence(
                new GeneralName[] {
                    new GeneralName(GeneralName.iPAddress, "127.0.0.1")
                }
            )
        );
        certBuilder.addExtension(
                Extension.subjectAlternativeName,
                true,
                subjectAltNames
        );

        // Sign the certificate
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .build(ca.keyPair().getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter()
                .getCertificate(certBuilder.build(signer));


        return new X509Credentials(serverKeyPair, cert);
    }

    /**
     * Creates a client certificate signed by the CA
     * @param ca The Certificate Authority to sign with
     * @param subjectName The client's common name
     * @param validityDays How long the certificate should be valid for
     * @return KeyPair containing the client's private key and certificate
     */
    public static X509Credentials createClientCertificate(X509Credentials ca, X500Name subjectName, int validityDays)
            throws Exception {
        // Generate client key pair
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        KeyPair clientKeyPair = keyPairGenerator.generateKeyPair();

        // Prepare certificate data
        X500Name issuerName = new X500Name(ca.certificate().getSubjectX500Principal().getName());
        BigInteger serialNumber = new BigInteger(160, new SecureRandom());
        Instant now = Instant.now();
        Date startDate = Date.from(now);
        Date endDate = Date.from(now.plus(validityDays, ChronoUnit.DAYS));

        // Create certificate builder
        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                issuerName,
                serialNumber,
                startDate,
                endDate,
                subjectName,
                clientKeyPair.getPublic()
        );

        // Add client certificate extensions
        certBuilder.addExtension(
                Extension.basicConstraints,
                true,
                new BasicConstraints(false)
        );
        certBuilder.addExtension(
                Extension.keyUsage,
                true,
                new KeyUsage(KeyUsage.digitalSignature)
        );
        certBuilder.addExtension(
                Extension.extendedKeyUsage,
                true,
                new ExtendedKeyUsage(new KeyPurposeId[]{KeyPurposeId.id_kp_clientAuth})
        );

        // Sign the certificate
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .build(ca.keyPair().getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter()
                .getCertificate(certBuilder.build(signer));

        return new X509Credentials(clientKeyPair, cert);
    }
}