-keep class com.arkanefans.mnn_engine.runtime.MnnNativeBridge { *; }
-keep class com.arkanefans.mnn_engine.runtime.MnnNativeSession { *; }
-keep interface com.arkanefans.mnn_engine.runtime.MnnNativeSession$TokenCallback { *; }

# Ktor Netty references optional integrations that are not used by the
# cleartext loopback server. Keep these rules narrow so missing Netty core
# dependencies still fail release builds.
-dontwarn io.netty.internal.tcnative.**
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.bouncycastle.asn1.pkcs.PrivateKeyInfo
-dontwarn org.bouncycastle.openssl.**
-dontwarn org.bouncycastle.operator.InputDecryptorProvider
-dontwarn org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
-dontwarn org.conscrypt.**
-dontwarn org.eclipse.jetty.npn.**
-dontwarn reactor.blockhound.integration.BlockHoundIntegration
