import type { I18nextToolkitConfig, CandidateString, TransformResult, ComponentBoundary, LanguageChangeSite } from '../../types.js';
interface TransformerOptions {
    isDryRun?: boolean;
    hasReact: boolean;
    isPrimaryLanguageFile: boolean;
    config: Omit<I18nextToolkitConfig, 'plugins'>;
    /** Detected React function component boundaries (used for hook injection) */
    components?: ComponentBoundary[];
    /** Target namespace for extracted keys (omit to use defaultNS) */
    namespace?: string;
    /** Detected language-change call sites to augment with i18n.changeLanguage() */
    languageChangeSites?: LanguageChangeSite[];
}
/**
 * Transforms a source file, replacing candidate strings with instrumented code.
 * Also injects useTranslation() hooks into React function components that
 * contain transformed strings.
 *
 * @param content - Original source code
 * @param file - File path
 * @param candidates - Candidate strings to transform
 * @param options - Transformation options
 * @returns TransformResult with modified content and diff
 */
export declare function transformFile(content: string, file: string, candidates: CandidateString[], options: TransformerOptions): TransformResult;
/**
 * Generates a unified diff showing what changed.
 */
export declare function generateDiff(original: string, modified: string, filePath: string): string;
export {};
//# sourceMappingURL=transformer.d.ts.map