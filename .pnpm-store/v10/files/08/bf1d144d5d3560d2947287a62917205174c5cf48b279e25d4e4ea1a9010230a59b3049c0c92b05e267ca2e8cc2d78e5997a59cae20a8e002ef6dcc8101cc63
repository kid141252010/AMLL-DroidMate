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
// ────────────────────────────────────────────────────────────────────────
// Translatable attributes
// ────────────────────────────────────────────────────────────────────────
/**
 * JSX/HTML attribute names whose values are typically user-visible and
 * should be translated.
 *
 * This is the recommended accepted-list for the linter **and** the set used
 * by the instrumenter's string-detector to allow attribute values through.
 *
 * Exported from the public API as `recommendedAcceptedAttributes`.
 */
const translatableAttributes = [
    'abbr', 'accesskey', 'alt',
    'aria-description', 'aria-label', 'aria-placeholder',
    'aria-roledescription', 'aria-valuetext',
    'content', 'description',
    'label', 'placeholder',
    'summary', 'caption', 'title'
];
/**
 * Pre-built Set (lower-cased) for fast membership checks in hot loops.
 */
const translatableAttributeSet = new Set(translatableAttributes.map(s => s.toLowerCase()));
// ────────────────────────────────────────────────────────────────────────
// Non-translatable (ignored) attributes
// ────────────────────────────────────────────────────────────────────────
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
const ignoredAttributes = [
    // CSS / styling
    'className', 'class', 'style',
    // Identity / keys
    'key', 'id', 'htmlFor', 'for', 'name',
    // Links & resources
    'href', 'src', 'srcSet', 'action',
    // HTML form / behaviour
    'type', 'target', 'rel', 'role', 'method', 'encType',
    'autoComplete', 'autoFocus', 'tabIndex',
    // Testing
    'data-testid', 'data-cy',
    // i18next-specific (already instrumented / extractor config)
    'i18nKey', 'defaults', 'ns', 'defaultValue',
    // Event handlers (representative set — the instrumenter also does a
    // prefix check for `on[A-Z]`)
    'onChange', 'onClick', 'onSubmit', 'onFocus', 'onBlur',
    'onKeyDown', 'onKeyUp', 'onMouseEnter', 'onMouseLeave',
    // React internals
    'ref', 'dangerouslySetInnerHTML', 'suppressHydrationWarning'
];
/**
 * Pre-built Set for fast membership checks.
 * Values are stored in their original casing — the instrumenter checks the
 * raw SWC attribute name.  The linter lower-cases before lookup.
 */
const ignoredAttributeSet = new Set(ignoredAttributes);
/**
 * Same set, lower-cased — used by the linter which normalises attr names.
 */
const ignoredAttributeLowerSet = new Set(ignoredAttributes.map(s => s.toLowerCase()));
// ────────────────────────────────────────────────────────────────────────
// Translatable object properties (instrumenter-specific)
// ────────────────────────────────────────────────────────────────────────
/**
 * Object / JSON property names whose values are typically user-visible and
 * should be translated.  Used by the instrumenter's string-detector to give
 * a confidence boost.
 */
const translatableProperties = [
    'label', 'title', 'description', 'text', 'message', 'placeholder',
    'caption', 'summary', 'heading', 'subheading', 'subtitle', 'tooltip',
    'hint', 'helpText', 'errorMessage', 'successMessage', 'name'
];
const translatablePropertySet = new Set(translatableProperties);
// ────────────────────────────────────────────────────────────────────────
// Ignored tags (linter + potential future instrumenter use)
// ────────────────────────────────────────────────────────────────────────
/**
 * HTML/JSX tags whose content should be ignored when linting for hardcoded
 * strings (e.g. `<script>`, `<style>`, `<code>`).
 */
const ignoredTags = ['script', 'style', 'code'];
/**
 * Recommended accepted tags — the set of tags the linter considers as
 * potentially containing translatable content.
 *
 * Exported from the public API as `recommendedAcceptedTags`.
 */
const acceptedTags = [
    'a', 'abbr', 'address', 'article', 'aside', 'bdi', 'bdo', 'blockquote',
    'button', 'caption', 'cite', 'code', 'data', 'dd', 'del', 'details',
    'dfn', 'dialog', 'div', 'dt', 'em', 'figcaption', 'footer',
    'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'header',
    'img', 'ins', 'kbd', 'label', 'legend', 'li', 'main', 'mark', 'nav',
    'option', 'output', 'p', 'pre', 'q', 's', 'samp', 'section', 'small',
    'span', 'strong', 'sub', 'summary', 'sup', 'td', 'textarea', 'th',
    'time', 'title', 'var'
];
new Set(acceptedTags.map(s => s.toLowerCase()));

export { acceptedTags, ignoredAttributeLowerSet, ignoredAttributeSet, ignoredAttributes, ignoredTags, translatableAttributeSet, translatableAttributes, translatableProperties, translatablePropertySet };
