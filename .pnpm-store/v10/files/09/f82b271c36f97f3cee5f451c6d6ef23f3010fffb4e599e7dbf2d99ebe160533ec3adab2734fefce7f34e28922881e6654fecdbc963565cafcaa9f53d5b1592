import React, { forwardRef, useCallback, useRef, } from "react";
import { squircleObserver } from "./corner-smoothing-vanilla";
const omitUndefined = (obj) => {
    const result = {};
    for (const key in obj) {
        if (obj[key] !== undefined) {
            // @ts-ignore
            result[key] = obj[key];
        }
    }
    return result;
};
export const Squircle = forwardRef(({ children, as: Component = "div", cornerRadius, topLeftCornerRadius, topRightCornerRadius, bottomRightCornerRadius, bottomLeftCornerRadius, cornerSmoothing, preserveSmoothing, borderWidth, ...rest }, forwardedRef) => {
    const funcRef = useRef();
    const refCallback = useCallback((el) => {
        const opts = omitUndefined({
            cornerRadius,
            topLeftCornerRadius,
            topRightCornerRadius,
            bottomRightCornerRadius,
            bottomLeftCornerRadius,
            cornerSmoothing,
            preserveSmoothing,
            borderWidth,
        });
        funcRef.current?.disconnect();
        if (el) {
            funcRef.current = squircleObserver(el, opts);
        }
        if (typeof forwardedRef === "function") {
            forwardedRef(el);
        }
        else if (forwardedRef) {
            forwardedRef.current = el;
        }
    }, [
        cornerRadius,
        topLeftCornerRadius,
        topRightCornerRadius,
        bottomRightCornerRadius,
        bottomLeftCornerRadius,
        cornerSmoothing,
        preserveSmoothing,
        borderWidth,
    ]);
    return (React.createElement(Component, { ref: refCallback, ...rest }, children));
});
/**
 * HOC that wraps `Component` and injects the squircle style.
 */
export const squircle = (Component, opts) => forwardRef((props, forwardedRef) => {
    const funcRef = useRef();
    const refCallback = useCallback((el) => {
        funcRef.current?.disconnect();
        if (el) {
            funcRef.current = squircleObserver(el, opts);
        }
        if (typeof forwardedRef === "function") {
            forwardedRef(el);
        }
        else if (forwardedRef) {
            forwardedRef.current = el;
        }
    }, [opts]);
    // @ts-ignore
    return React.createElement(Component, { ref: refCallback, ...props });
});
//# sourceMappingURL=corner-smoothing-react.js.map