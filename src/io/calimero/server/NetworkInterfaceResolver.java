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

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;

import java.lang.System.Logger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.calimero.KNXIllegalArgumentException;
import io.calimero.KnxRuntimeException;
import io.calimero.log.LogService;

/**
 * Resolves a configured network-interface name at use-time, not at XML parse time.
 * <p>
 * Host-network containers often start before {@code end1} exists or has a routable IPv4 address, and DHCP can
 * later move that address. Callers wait for a usable address, bind it, and re-resolve when the interface moves.
 * <p>
 * "Usable" means: the interface exists, is up, and carries an IPv4 address that is neither loopback nor
 * link-local. A self-assigned APIPA address ({@code 169.254/16}) is a failure, not a success -- binding one
 * publishes an unroutable endpoint in the KNXnet/IP HPAI while the server logs a healthy start.
 */
public final class NetworkInterfaceResolver
{
	private static final Logger logger = LogService.getLogger("io.calimero.server.netif");

	public static final Duration DEFAULT_WAIT = Duration.ofSeconds(60);
	public static final Duration DEFAULT_RETRY = Duration.ofMillis(500);

	/** Upper bound for a configured wait, so a bad property cannot overflow the deadline arithmetic. */
	private static final Duration MAX_WAIT = Duration.ofHours(1);

	private NetworkInterfaceResolver() {}

	/**
	 * Thrown when a wait was cut short by thread interruption, as opposed to the interface never appearing.
	 * Callers use this to log a shutdown at DEBUG instead of reporting a spurious initialization failure.
	 */
	public static final class InterfaceWaitInterruptedException extends KnxRuntimeException
	{
		private static final long serialVersionUID = 1L;

		InterfaceWaitInterruptedException(final String msg, final Throwable cause) { super(msg, cause); }
	}

	/**
	 * The single definition of "no specific interface" in this process. {@link Launcher} and
	 * {@link io.calimero.server.knxnetip.ControlEndpointService} both defer to this; a second, differing
	 * notion of "any" elsewhere is what lets a name such as {@code ALL} fall through to a wildcard bind.
	 */
	public static boolean isAny(final String name) {
		return name == null || name.isBlank() || "any".equalsIgnoreCase(name.trim());
	}

	/**
	 * Warn, at config-parse time, that a configured interface is not usable yet, listing the interfaces that do
	 * exist. A typo in {@code netif} is otherwise indistinguishable from an interface that is merely slow to
	 * appear -- both just wait -- and this single line is what lets a field engineer tell them apart.
	 */
	public static void warnIfNotUsableYet(final String name) {
		if (isAny(name))
			return;
		try {
			if (usableAddresses(name).findAny().isPresent())
				return;
		}
		catch (final SocketException e) {
			// fall through and warn: we could not confirm it either
		}
		logger.log(WARNING, "network interface ''{0}'' has no usable IPv4 address yet; it will be awaited when "
				+ "the endpoint binds. Interfaces present now: {1}", name, presentInterfaceNames());
	}

	/** Interface names present on this host, so a typo in the configuration is diagnosable from one log line. */
	private static String presentInterfaceNames() {
		try {
			return NetworkInterface.networkInterfaces().map(NetworkInterface::getName)
					.collect(Collectors.joining(", "));
		}
		catch (final SocketException e) {
			return "<unavailable: " + e.getMessage() + ">";
		}
	}

	public static Duration waitTimeout() {
		return durationProperty("io.calimero.server.netif.waitMillis", DEFAULT_WAIT);
	}

	public static Duration retryInterval() {
		return durationProperty("io.calimero.server.netif.retryMillis", DEFAULT_RETRY);
	}

	/** An IPv4 address we are willing to bind and advertise: not loopback, not link-local (APIPA). */
	public static boolean usableAddress(final InetAddress addr) {
		return addr instanceof Inet4Address && !addr.isLoopbackAddress() && !addr.isLinkLocalAddress();
	}

	/**
	 * Usable IPv4 addresses of the named interface, best candidate first. Empty for an "any" name, an unknown
	 * or down interface, or an interface holding no routable IPv4 address.
	 *
	 * @throws SocketException if the interface cannot be queried; callers waiting on the interface treat this
	 *         as "not yet usable" rather than as a failure
	 */
	public static Stream<InetAddress> usableAddresses(final String name) throws SocketException {
		if (isAny(name))
			return Stream.empty();
		final NetworkInterface nif = NetworkInterface.getByName(name.trim());
		if (nif == null || !nif.isUp())
			return Stream.empty();
		// site-local last only as a tie-break; the ordering is stable so repeated binds pick the same address
		final List<InetAddress> usable = nif.inetAddresses().filter(NetworkInterfaceResolver::usableAddress)
				.sorted(Comparator.comparing(InetAddress::isSiteLocalAddress)
						.thenComparing(InetAddress::getHostAddress))
				.toList();
		return usable.stream();
	}

	/** Instant lookup of the interface itself. Empty if the name is any/unknown/down/no usable IPv4. */
	public static Optional<NetworkInterface> findUsable(final String name) {
		try {
			if (isAny(name))
				return Optional.empty();
			final NetworkInterface nif = NetworkInterface.getByName(name.trim());
			if (nif == null || !nif.isUp())
				return Optional.empty();
			final boolean usable = nif.inetAddresses().anyMatch(NetworkInterfaceResolver::usableAddress);
			return usable ? Optional.of(nif) : Optional.empty();
		}
		catch (final SocketException e) {
			throw new KnxRuntimeException("while searching for network interface '" + name + "'", e);
		}
	}

	/** Instant lookup of the address to bind. Empty for an "any" name or an interface that is not usable yet. */
	public static Optional<InetAddress> findBindAddress(final String name) {
		try {
			return usableAddresses(name).findFirst();
		}
		catch (final SocketException e) {
			throw new KnxRuntimeException("while searching for network interface '" + name + "'", e);
		}
	}

	/**
	 * Wait until {@code name} carries a usable IPv4 address and return that address. Lookup, up-check, address
	 * filtering and selection happen in one pass, so the caller binds exactly what was validated -- there is no
	 * second, weaker lookup in between.
	 *
	 * @return the address to bind, or empty if {@code name} is "any" (the caller keeps its own default)
	 * @throws KNXIllegalArgumentException if the interface never became usable within the wait
	 */
	public static Optional<InetAddress> awaitBindAddress(final String name) {
		return awaitBindAddress(name, waitTimeout(), retryInterval());
	}

	public static Optional<InetAddress> awaitBindAddress(final String name, final Duration wait,
			final Duration retry) {
		return awaitBindAddress(name, wait, retry, () -> usableAddresses(name).findFirst());
	}

	/**
	 * Probes the current usable address of an interface. Exists so tests can drive the wait loop's timing and
	 * failure paths -- appearing mid-wait, a transient {@link SocketException}, expiry -- without needing root
	 * or a real interface to appear on cue.
	 */
	@FunctionalInterface
	interface AddressProbe {
		Optional<InetAddress> probe() throws SocketException;
	}

	static Optional<InetAddress> awaitBindAddress(final String name, final Duration wait, final Duration retry,
			final AddressProbe probe) {
		if (isAny(name))
			return Optional.empty();

		final long waitNanos = Math.max(0, clampWait(wait).toNanos());
		final long deadline = System.nanoTime() + waitNanos;
		long lastLog = 0;
		boolean waited = false;
		while (true) {
			Optional<InetAddress> found = Optional.empty();
			try {
				found = probe.probe();
			}
			catch (final SocketException e) {
				// the interface is being torn down or re-created (DHCP, udev): not yet usable, keep waiting
				logger.log(DEBUG, "network interface {0} not queryable yet: {1}", name, e.getMessage());
			}
			if (found.isPresent()) {
				if (waited)
					logger.log(INFO, "network interface {0} is up with address {1}", name,
							found.get().getHostAddress());
				return found;
			}
			if (System.nanoTime() >= deadline)
				throw new KNXIllegalArgumentException("no usable network interface '" + name + "' (up, with a "
						+ "non-loopback non-link-local IPv4 address) after " + waitNanos / 1_000_000L + " ms");

			final long now = System.nanoTime();
			if (!waited || now - lastLog >= Duration.ofSeconds(10).toNanos()) {
				logger.log(WARNING, "waiting for network interface {0} (up, routable IPv4), timeout {1} ms",
						name, waitNanos / 1_000_000L);
				lastLog = now;
			}
			waited = true;
			sleep(retry);
		}
	}

	private static Duration clampWait(final Duration wait) {
		if (wait == null || wait.isNegative())
			return Duration.ZERO;
		return wait.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : wait;
	}

	private static Duration durationProperty(final String key, final Duration fallback) {
		final String raw = System.getProperty(key);
		if (raw == null || raw.isBlank())
			return fallback;
		try {
			final long millis = Long.parseLong(raw.trim());
			if (millis < 0) {
				logger.log(WARNING, "ignoring negative value for {0}: ''{1}'', using {2} ms", key, raw,
						fallback.toMillis());
				return fallback;
			}
			return clampWait(Duration.ofMillis(millis));
		}
		catch (final NumberFormatException e) {
			logger.log(WARNING, "ignoring unparsable value for {0}: ''{1}'' (expected milliseconds), using {2} ms",
					key, raw, fallback.toMillis());
			return fallback;
		}
	}

	private static void sleep(final Duration retry) {
		try {
			Thread.sleep(Math.max(1, retry.toMillis()));
		}
		catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new InterfaceWaitInterruptedException("interrupted waiting for network interface", e);
		}
	}
}
