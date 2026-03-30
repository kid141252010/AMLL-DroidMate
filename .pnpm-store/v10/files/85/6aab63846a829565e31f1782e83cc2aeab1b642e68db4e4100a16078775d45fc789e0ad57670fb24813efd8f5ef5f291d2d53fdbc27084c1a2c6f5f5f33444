/**
 * Generates camelCase keys from English string content.
 *
 * Examples:
 *   "Welcome back" → "welcomeBack"
 *   "Hello, World!" → "helloWorld"
 *   "You have 3 items" → "youHave3Items"
 *
 * @param content - The string content to derive a key from
 * @returns camelCase key
 */
function generateKeyFromContent(content) {
    // Remove punctuation and split by whitespace and word boundaries
    const normalized = content
        .replace(/[^\w\s\d]/g, '') // Remove punctuation
        .trim();
    if (!normalized) {
        return 'key';
    }
    // Split on whitespace and camelCase
    const words = normalized.split(/\s+/);
    const camelCased = words
        .map((word, index) => {
        if (index === 0) {
            return word.toLowerCase();
        }
        return word.charAt(0).toUpperCase() + word.slice(1).toLowerCase();
    })
        .join('');
    // If result is empty, use fallback
    return camelCased.length > 0 ? camelCased : 'key';
}
/**
 * Creates a new key registry with collision detection.
 */
function createKeyRegistry() {
    const keys = new Map();
    return {
        keys,
        add(baseKey, content) {
            const existing = keys.get(baseKey);
            // No collision - add the key
            if (!existing) {
                keys.set(baseKey, content);
                return baseKey;
            }
            // Same content already exists - return the existing key
            if (existing === content) {
                return baseKey;
            }
            // Collision detected - try with numeric suffixes
            let counter = 2;
            let candidateKey = `${baseKey}${counter}`;
            while (keys.has(candidateKey)) {
                const candidate = keys.get(candidateKey);
                if (candidate === content) {
                    return candidateKey; // This exact content already has a numbered key
                }
                counter++;
                candidateKey = `${baseKey}${counter}`;
            }
            keys.set(candidateKey, content);
            return candidateKey;
        }
    };
}

export { createKeyRegistry, generateKeyFromContent };
