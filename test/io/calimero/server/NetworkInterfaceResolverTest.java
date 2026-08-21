package io.calimero.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.calimero.KNXIllegalArgumentException;

class NetworkInterfaceResolverTest
{
	@Test
	void anyIsNotResolved()
	{
		assertTrue(NetworkInterfaceResolver.isAny("any"));
		assertTrue(NetworkInterfaceResolver.isAny("all"));
		assertTrue(NetworkInterfaceResolver.isAny(null));
		assertTrue(NetworkInterfaceResolver.awaitUsable("any").isEmpty());
	}

	@Test
	void missingInterfaceIsEmptyOnFind()
	{
		assertTrue(NetworkInterfaceResolver.findUsable("end1-does-not-exist").isEmpty());
	}

	@Test
	void awaitMissingInterfaceFailsAfterTimeout()
	{
		final var err = assertThrows(KNXIllegalArgumentException.class, () -> NetworkInterfaceResolver
				.awaitUsable("end1-does-not-exist", Duration.ofMillis(80), Duration.ofMillis(20)));
		assertTrue(err.getMessage().contains("end1-does-not-exist"));
	}

	@Test
	void loopbackIsUsableIfPresent() throws Exception
	{
		final var lo = NetworkInterface.getByName("lo");
		if (lo == null)
			return;
		assertTrue(NetworkInterfaceResolver.findUsable("lo").isPresent()
				|| !lo.isUp()
				|| lo.inetAddresses().noneMatch(a -> a instanceof java.net.Inet4Address));
	}

	@Test
	void xmlParseDoesNotCrashWhenNetifAbsent() throws Exception
	{
		final Path xml = Files.createTempFile("calimero-netif-", ".xml");
		Files.writeString(xml, """
				<knxServer name="knx-server" friendlyName="test">
					<discovery listenNetIf="end1-does-not-exist" outgoingNetIf="end1-does-not-exist" activate="true"/>
					<serviceContainer activate="true" routing="false" networkMonitoring="true" udpPort="3671" netif="end1-does-not-exist">
						<knxAddress type="individual">1.1.1</knxAddress>
						<knxSubnet type="tpuart">/dev/null</knxSubnet>
					</serviceContainer>
				</knxServer>
				""", StandardCharsets.UTF_8);
		assertDoesNotThrow(() -> Launcher.XmlConfiguration.from(xml.toUri()));
		Files.deleteIfExists(xml);
	}

	@Test
	void xmlParseKeepsConfiguredName() throws Exception
	{
		final Path xml = Files.createTempFile("calimero-netif-", ".xml");
		Files.writeString(xml, """
				<knxServer name="knx-server" friendlyName="test">
					<discovery listenNetIf="any" outgoingNetIf="any" activate="false"/>
					<serviceContainer activate="false" routing="false" networkMonitoring="false" udpPort="3671" netif="end1">
						<knxAddress type="individual">1.1.1</knxAddress>
						<knxSubnet type="tpuart">/dev/null</knxSubnet>
					</serviceContainer>
				</knxServer>
				""", StandardCharsets.UTF_8);
		final var config = Launcher.XmlConfiguration.from(xml.toUri());
		assertFalse(config.containers().isEmpty());
		assertTrue("end1".equals(config.containers().getFirst().subnetConnector().getServiceContainer().networkInterface()));
		Files.deleteIfExists(xml);
	}
}
