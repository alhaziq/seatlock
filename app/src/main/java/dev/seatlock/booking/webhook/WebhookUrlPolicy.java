package dev.seatlock.booking.webhook;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** SSRF guard: decides whether an organizer-supplied URL may be called. Used at registration and before each send. */
@Component
public class WebhookUrlPolicy {

	private final boolean allowHttp;

	private final boolean allowPrivateAddresses;

	public WebhookUrlPolicy(@Value("${seatlock.webhooks.allow-http}") boolean allowHttp,
			@Value("${seatlock.webhooks.allow-private-addresses}") boolean allowPrivateAddresses) {
		this.allowHttp = allowHttp;
		this.allowPrivateAddresses = allowPrivateAddresses;
	}

	public URI check(String url) {
		URI uri;
		try {
			uri = new URI(url);
		}
		catch (URISyntaxException e) {
			throw new InvalidWebhookUrlException("url is not a valid URI");
		}
		if (!uri.isAbsolute()) {
			throw new InvalidWebhookUrlException("url must be absolute");
		}
		String scheme = uri.getScheme().toLowerCase();
		if (!scheme.equals("https") && !(allowHttp && scheme.equals("http"))) {
			throw new InvalidWebhookUrlException("url scheme must be https");
		}
		if (uri.getUserInfo() != null) {
			throw new InvalidWebhookUrlException("url must not contain user info");
		}
		String host = uri.getHost();
		if (host == null || host.isEmpty()) {
			throw new InvalidWebhookUrlException("url must have a host");
		}
		if (!allowPrivateAddresses) {
			try {
				for (InetAddress a : InetAddress.getAllByName(host)) {
					if (isPrivate(a)) {
						throw new InvalidWebhookUrlException("url host resolves to a non-public address");
					}
				}
			}
			catch (UnknownHostException e) {
				throw new InvalidWebhookUrlException("url host cannot be resolved");
			}
		}
		return uri;
	}

	private static boolean isPrivate(InetAddress a) {
		return a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
				|| a.isMulticastAddress()
				|| (a instanceof Inet6Address && (a.getAddress()[0] & 0xfe) == 0xfc);
	}

}
