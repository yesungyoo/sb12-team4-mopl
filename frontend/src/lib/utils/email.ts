/**
 * Masks an email address to protect privacy
 * @param email - The email address to mask
 * @returns Masked email (e.g., "co******@codeit.com")
 * @example
 * maskEmail("codeit@codeit.com") // "co******@codeit.com"
 * maskEmail("a@example.com") // "a*****@example.com"
 */
export function maskEmail(email: string): string {
  const [local, domain] = email.split('@');

  if (!local || !domain) {
    return email; // Return original if invalid format
  }

  // Show first 2 characters, then mask the rest with at least 6 asterisks
  const visibleChars = Math.min(2, local.length);
  const maskLength = Math.max(local.length - visibleChars, 6);
  const maskedLocal = local.slice(0, visibleChars) + '*'.repeat(maskLength);

  return `${maskedLocal}@${domain}`;
}
