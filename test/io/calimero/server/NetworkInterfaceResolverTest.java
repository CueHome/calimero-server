/*
    Calimero 3 - A library for KNX network access
    Copyright (c) 2010, 2025 B. Malinowsky
    Copyright (c) 2026 CueHome

    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program; if not, write to the Free Software
    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

    Linking this library statically or dynamically with other modules is
    making a combined work based on this library. Thus, the terms and
    conditions of the GNU General Public License cover the whole
    combination.

    As a special exception, the copyright holders of this library give you
    permission to link this library with independent modules to produce an
    executable, regardless of the license terms of these independent
    modules, and to copy and distribute the resulting executable under terms
    of your choice, provided that you also meet, for each linked independent
    module, the terms and conditions of the license of that module. An
    independent module is a module which is not derived from or based on
    this library. If you modify this library, you may extend this exception
    to your version of the library, but you are not obligated to do so. If
    you do not wish to do so, delete this exception statement from your
    version.
*/
package io.calimero.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.calimero.KNXIllegalArgumentException;

class NetworkInterfaceResolverTest
{
	@TempDir
	Path tmp;

	private static final InetAddress GLOBAL = address(192, 168, 1, 73);
	private static final InetAddress APIPA = address(169, 254, 12, 34);

	private static InetAddress address(final int a, final int b, final int c, final int d)
	{
		try {
			return InetAddress.getByAddress(new byte[] { (byte) a, (byte) b, (byte) c, (byte) d });
		}
		catch (final Exception e) {
			throw new AssertionError(e);
		}
	}

	@Test
	void anyIsNotResolved()
	{
		assertTrue(NetworkInterfaceResolver.isAny("any"));
		assertTrue(NetworkInterfaceResolver.isAny("ANY"));
		assertTrue(NetworkInterfaceResolver.isAny(null));
		assertTrue(NetworkInterfaceResolver.isAny("  "));
		assertTrue(NetworkInterfaceResolver.awaitBindAddress("any").isEmpty());
	}

	/**
	 * "any" must mean exactly what it means to the bind path. If this helper were broader than the callers'
	 * notion of "any", a name such as "ALL" would be reported as any here, skip the wait, and then miss the
	 * named branch downstream -- landing on a wildcard bind that publishes an unroutable HPAI.
	 */
	@Test
	void aliasesAreNotTreatedAsAny()
	{
		assertFalse(NetworkInterfaceResolver.isAny("all"));
		assertFalse(NetworkInterfaceResolver.isAny("default"));
		assertFalse(NetworkInterfaceResolver.isAny("end1"));
	}

	@Test
	void missingInterfaceIsEmptyOnFind()
	{
		assertTrue(NetworkInterfaceResolver.findUsable("end1-does-not-exist").isEmpty());
		assertTrue(NetworkInterfaceResolver.findBindAddress("end1-does-not-exist").isEmpty());
	}

	@Test
	void awaitMissingInterfaceFailsAfterTimeout()
	{
		final var err = assertThrows(KNXIllegalArgumentException.class, () -> NetworkInterfaceResolver
				.awaitBindAddress("end1-does-not-exist", Duration.ofMillis(80), Duration.ofMillis(20)));
		assertTrue(err.getMessage().contains("end1-does-not-exist"));
	}

	/** Expiry must fail, and must not read as though a wildcard bind were an acceptable outcome. */
	@Test
	void namedWaitExpiryThrowsAndDoesNotImplyWildcard()
	{
		final long start = System.nanoTime();
		final var err = assertThrows(KNXIllegalArgumentException.class,
				() -> NetworkInterfaceResolver.awaitBindAddress("end1", Duration.ofMillis(120),
						Duration.ofMillis(20), Optional::empty));
		final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
		assertTrue(elapsedMs >= 120, "must consume the wait budget, took " + elapsedMs + " ms");
		assertFalse(err.getMessage().contains("0.0.0.0"));
		assertFalse(err.getMessage().toLowerCase().contains("wildcard"));
		assertTrue(err.getMessage().contains("ms"), "duration reported in ms: " + err.getMessage());
	}

	/** A sub-second wait must not be reported as "0s". */
	@Test
	void subSecondWaitIsReportedInMilliseconds()
	{
		final var err = assertThrows(KNXIllegalArgumentException.class,
				() -> NetworkInterfaceResolver.awaitBindAddress("end1", Duration.ofMillis(50),
						Duration.ofMillis(10), Optional::empty));
		assertTrue(err.getMessage().contains("50 ms"), err.getMessage());
	}

	/** The interface appearing part-way through the wait is the whole point of the change. */
	@Test
	void interfaceAppearingMidWaitIsPickedUp()
	{
		final var attempts = new AtomicInteger();
		final var found = NetworkInterfaceResolver.awaitBindAddress("end1", Duration.ofSeconds(5),
				Duration.ofMillis(20), () -> attempts.incrementAndGet() < 3 ? Optional.empty() : Optional.of(GLOBAL));
		assertEquals(Optional.of(GLOBAL), found);
		assertTrue(attempts.get() >= 3, "expected to poll until it appeared, polls: " + attempts.get());
	}

	/**
	 * An interface being reconfigured by DHCP or udev makes the OS lookup fail transiently. That is the very
	 * condition this class exists to survive, so it must keep waiting rather than abandon the wait.
	 */
	@Test
	void transientSocketExceptionKeepsWaiting()
	{
		final var attempts = new AtomicInteger();
		final var found = NetworkInterfaceResolver.awaitBindAddress("end1", Duration.ofSeconds(5),
				Duration.ofMillis(20), () -> {
					if (attempts.incrementAndGet() < 3)
						throw new SocketException("ENODEV: interface is being reconfigured");
					return Optional.of(GLOBAL);
				});
		assertEquals(Optional.of(GLOBAL), found);
		assertTrue(attempts.get() >= 3);
	}

	/** Shutdown during a wait must be distinguishable from "the interface never appeared". */
	@Test
	void interruptDuringWaitIsDistinguishableAndRestoresFlag() throws Exception
	{
		final var caught = new AtomicReference<Throwable>();
		final var interruptFlagSeen = new AtomicReference<Boolean>();
		final var worker = new Thread(() -> {
			try {
				NetworkInterfaceResolver.awaitBindAddress("end1", Duration.ofSeconds(30), Duration.ofMillis(50),
						Optional::empty);
			}
			catch (final Throwable t) {
				caught.set(t);
				interruptFlagSeen.set(Thread.currentThread().isInterrupted());
			}
		});
		worker.start();
		Thread.sleep(120);
		worker.interrupt();
		worker.join(5000);

		assertTrue(caught.get() instanceof NetworkInterfaceResolver.InterfaceWaitInterruptedException,
				"expected a distinguishable interrupt failure, got " + caught.get());
		assertFalse(caught.get() instanceof KNXIllegalArgumentException,
				"an interrupt must not masquerade as the interface never appearing");
		assertTrue(interruptFlagSeen.get(), "interrupt flag must be restored");
	}

	/** APIPA is a DHCP failure, not a usable address: binding it publishes an unroutable HPAI. */
	@Test
	void linkLocalIsNotUsable()
	{
		assertFalse(NetworkInterfaceResolver.usableAddress(APIPA));
		assertTrue(NetworkInterfaceResolver.usableAddress(GLOBAL));
	}

	@Test
	void loopbackIsNotUsable() throws Exception
	{
		final var lo = NetworkInterface.networkInterfaces().filter(nif -> {
			try {
				return nif.isLoopback();
			}
			catch (final SocketException e) {
				return false;
			}
		}).findFirst().orElseThrow(() -> new AssertionError("no loopback interface on this host"));

		assertTrue(lo.inetAddresses().noneMatch(NetworkInterfaceResolver::usableAddress),
				"loopback addresses must never count as usable");
		assertTrue(NetworkInterfaceResolver.findUsable(lo.getName()).isEmpty());
		assertTrue(NetworkInterfaceResolver.findBindAddress(lo.getName()).isEmpty());
	}

	@Test
	void badPropertyValuesFallBackInsteadOfThrowing()
	{
		final String key = "io.calimero.server.netif.waitMillis";
		final String prior = System.getProperty(key);
		try {
			System.setProperty(key, "90s");
			assertEquals(NetworkInterfaceResolver.DEFAULT_WAIT, NetworkInterfaceResolver.waitTimeout());
			System.setProperty(key, "-1");
			assertEquals(NetworkInterfaceResolver.DEFAULT_WAIT, NetworkInterfaceResolver.waitTimeout());
			System.setProperty(key, String.valueOf(Long.MAX_VALUE));
			assertDoesNotThrow(NetworkInterfaceResolver::waitTimeout);
		}
		finally {
			if (prior == null)
				System.clearProperty(key);
			else
				System.setProperty(key, prior);
		}
	}

	@Test
	void xmlParseDoesNotCrashWhenNetifAbsent() throws Exception
	{
		final Path xml = tmp.resolve("netif-absent.xml");
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
	}

	@Test
	void xmlParseKeepsConfiguredName() throws Exception
	{
		final Path xml = tmp.resolve("netif-name.xml");
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
	}
}
