/*
    Calimero 3 - A library for KNX network access
    Copyright (c) 2026 CueHome

    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.
*/

package io.calimero.server;

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;

import java.lang.System.Logger;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import io.calimero.KNXIllegalArgumentException;
import io.calimero.KnxRuntimeException;
import io.calimero.log.LogService;

/**
 * Resolves a configured network-interface name at use-time, not at XML parse time.
 * Host-network containers often start before {@code end1} exists or has an IPv4 address;
 * DHCP can later move that address. Callers wait, then bind or re-bind.
 */
public final class NetworkInterfaceResolver
{
	private static final Logger logger = LogService.getLogger("io.calimero.server.netif");

	public static final Duration DEFAULT_WAIT = Duration.ofSeconds(60);
	public static final Duration DEFAULT_RETRY = Duration.ofMillis(500);

	private NetworkInterfaceResolver() {}

	public static boolean isAny(final String name) {
		return name == null || name.isBlank() || "any".equalsIgnoreCase(name) || "all".equalsIgnoreCase(name)
				|| "default".equalsIgnoreCase(name);
	}

	public static Duration waitTimeout() {
		return durationProperty("io.calimero.server.netif.waitMillis", DEFAULT_WAIT);
	}

	public static Duration retryInterval() {
		return durationProperty("io.calimero.server.netif.retryMillis", DEFAULT_RETRY);
	}

	/** Instant lookup. Empty if the name is any/unknown/down/no IPv4. */
	public static Optional<NetworkInterface> findUsable(final String name) {
		if (isAny(name))
			return Optional.empty();
		try {
			final NetworkInterface nif = NetworkInterface.getByName(name);
			if (nif == null || !nif.isUp())
				return Optional.empty();
			final boolean ipv4 = nif.inetAddresses().anyMatch(Inet4Address.class::isInstance);
			return ipv4 ? Optional.of(nif) : Optional.empty();
		}
		catch (final SocketException e) {
			throw new KnxRuntimeException("while searching for network interface '" + name + "'", e);
		}
	}

	/**
	 * Wait until {@code name} exists, is up, and has an IPv4 address.
	 * {@code any} returns empty immediately.
	 */
	public static Optional<NetworkInterface> awaitUsable(final String name) {
		return awaitUsable(name, waitTimeout(), retryInterval());
	}

	public static Optional<NetworkInterface> awaitUsable(final String name, final Duration wait,
			final Duration retry) {
		if (isAny(name))
			return Optional.empty();

		final long deadline = System.nanoTime() + Math.max(0, wait.toNanos());
		boolean logged = false;
		while (true) {
			final Optional<NetworkInterface> found = findUsable(name);
			if (found.isPresent()) {
				if (logged)
					logger.log(INFO, "network interface {0} is up with IPv4", name);
				return found;
			}
			if (System.nanoTime() >= deadline)
				throw new KNXIllegalArgumentException(
						"no usable network interface '" + name + "' after " + wait.toSeconds() + "s");
			if (!logged) {
				logger.log(WARNING, "waiting for network interface {0} (up + IPv4), timeout {1}s",
						name, wait.toSeconds());
				logged = true;
			}
			sleep(retry);
		}
	}

	private static Duration durationProperty(final String key, final Duration fallback) {
		final String raw = System.getProperty(key);
		if (raw == null || raw.isBlank())
			return fallback;
		try {
			return Duration.ofMillis(Long.parseLong(raw.trim()));
		}
		catch (final NumberFormatException e) {
			return fallback;
		}
	}

	private static void sleep(final Duration retry) {
		try {
			Thread.sleep(Math.max(1, retry.toMillis()));
		}
		catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new KnxRuntimeException("interrupted waiting for network interface", e);
		}
	}

	static String requireName(final String name) {
		return Objects.requireNonNullElse(name, "any");
	}
}
