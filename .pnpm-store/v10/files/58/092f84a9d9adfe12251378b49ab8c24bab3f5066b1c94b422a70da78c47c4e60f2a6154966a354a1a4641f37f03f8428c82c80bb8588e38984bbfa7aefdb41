/**
 * Shared constants for JSX / HTML attribute classification.
 *
 * Used by both the **linter** and the **instrumenter** to consistently decide
 * which JSX attribute values are user-facing (translatable) and which are
 * technical / non-translatable.
 *
 * Having a single source of truth avoids drift between the linter's
 * `defaultIgnoredAttributes` / `recommendedAcceptedAttributes` and the
 * instrumenter's `SKIP_JSX_ATTRIBUTES` / `TRANSLATABLE_ATTRIBUTES`.
 */
/**
 * JSX/HTML attribute names whose values are typically user-visible and
 * should be translated.
 *
 * This is the recommended accepted-list for the linter **and** the set used
 * by the instrumenter's string-detector to allow attribute values through.
 *
 * Exported from the public API as `recommendedAcceptedAttributes`.
 */
export declare const translatableAttributes: readonly string[];
/**
 * Pre-built Set (lower-cased) for fast membership checks in hot loops.
 */
export declare const translatableAttributeSet: ReadonlySet<string>;
/**
 * JSX attribute names whose values are **never** user-facing.
 *
 * The linter uses these as `defaultIgnoredAttributes`, and the instrumenter
 * skips recursing into them entirely so that e.g. `className={...}` is
 * never wrapped in `t()`.
 *
 * Event-handler attributes (on*) are handled separately via a prefix check
 * rather than being enumerated here, but a representative set is included
 * for the instrumenter's early-exit guard which does a Set lookup.
 */
export declare const ignoredAttributes: readonly string[];
/**
 * Pre-built Set for fast membership checks.
 * Values are stored in their original casing — the instrumenter checks the
 * raw SWC attribute name.  The linter lower-cases before lookup.
 */
export declare const ignoredAttributeSet: ReadonlySet<string>;
/**
 * Same set, lower-cased — used by the linter which normalises attr names.
 */
export declare const ignoredAttributeLowerSet: ReadonlySet<string>;
/**
 * Object / JSON property names whose values are typically user-visible and
 * should be translated.  Used by the instrumenter's string-detector to give
 * a confidence boost.
 */
export declare const translatableProperties: readonly string[];
export declare const translatablePropertySet: ReadonlySet<string>;
/**
 * HTML/JSX tags whose content should be ignored when linting for hardcoded
 * strings (e.g. `<script>`, `<style>`, `<code>`).
 */
export declare const ignoredTags: readonly string[];
/**
 * Recommended accepted tags — the set of tags the linter considers as
 * potentially containing translatable content.
 *
 * Exported from the public API as `recommendedAcceptedTags`.
 */
export declare const acceptedTags: readonly string[];
export declare const acceptedTagSet: ReadonlySet<string>;
//# sourceMappingURL=jsx-attributes.d.ts.map