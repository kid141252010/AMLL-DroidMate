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
export declare function generateKeyFromContent(content: string): string;
/**
 * Interface for tracking generated keys and managing collisions
 */
export interface KeyRegistry {
    keys: Map<string, string>;
    add(key: string, content: string): string;
}
/**
 * Creates a new key registry with collision detection.
 */
export declare function createKeyRegistry(): KeyRegistry;
/**
 * Sanitizes a generated key to be valid according to i18next conventions.
 * Removes invalid characters and ensures it's a valid JavaScript identifier
 * that can be used as an object key.
 */
export declare function sanitizeKey(key: string): string;
//# sourceMappingURL=key-generator.d.ts.map