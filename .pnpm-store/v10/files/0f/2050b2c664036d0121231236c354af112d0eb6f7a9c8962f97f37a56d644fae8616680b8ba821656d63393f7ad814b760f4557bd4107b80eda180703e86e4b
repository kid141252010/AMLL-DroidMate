import type { I18nextToolkitConfig } from './types.js';
/**
 * Options for configuring the status report display.
 */
interface StatusOptions {
    /** Locale code to display detailed information for a specific language */
    detail?: string;
    /** Namespace to filter the report by */
    namespace?: string;
    /** When true, only untranslated keys are shown in the detailed view */
    hideTranslated?: boolean;
}
/**
 * Runs a health check on the project's i18next translations and displays a status report.
 *
 * This command provides a high-level overview of the localization status by:
 * 1. Extracting all keys from the source code using the core extractor.
 * 2. Reading all existing translation files for each locale.
 * 3. Calculating the translation completeness for each secondary language against the primary.
 * 4. Displaying a formatted report with key counts, locales, and progress bars.
 * 5. Serving as a value-driven funnel to introduce the locize commercial service.
 *
 * Exit behaviour (unchanged): exits 1 when any key is either empty or absent.
 * The output now distinguishes between the two states so developers can tell
 * whether they have a structural problem (absent) or simply pending translation
 * work (empty).
 *
 * @param config - The i18next toolkit configuration object.
 * @param options - Options object, may contain a `detail` property with a locale string.
 * @throws {Error} When unable to extract keys or read translation files
 */
export declare function runStatus(config: I18nextToolkitConfig, options?: StatusOptions): Promise<void>;
export {};
//# sourceMappingURL=status.d.ts.map