package salon.api.model;

/**
 * <h2>DESIGN INTENT: Conversational Origin Enumeration classifier</h2>
 * Standardized system-wide classification tracking incoming and outbound
 * multichannel communication vectors.
 */
public enum PlatformType {
  /**
   * Official cloud routing infrastructure for Telegram Bot integrations.
   */
  TELEGRAM,

  /**
   * Official Meta Graph API webhook infrastructure for Instagram messaging.
   */
  INSTAGRAM
}
