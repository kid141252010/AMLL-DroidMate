"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || function (mod) {
    if (mod && mod.__esModule) return mod;
    var result = {};
    if (mod != null) for (var k in mod) if (k !== "default" && Object.prototype.hasOwnProperty.call(mod, k)) __createBinding(result, mod, k);
    __setModuleDefault(result, mod);
    return result;
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.squircle = exports.Squircle = void 0;
const react_1 = __importStar(require("react"));
const corner_smoothing_vanilla_1 = require("./corner-smoothing-vanilla");
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
exports.Squircle = (0, react_1.forwardRef)(({ children, as: Component = "div", cornerRadius, topLeftCornerRadius, topRightCornerRadius, bottomRightCornerRadius, bottomLeftCornerRadius, cornerSmoothing, preserveSmoothing, borderWidth, ...rest }, forwardedRef) => {
    const funcRef = (0, react_1.useRef)();
    const refCallback = (0, react_1.useCallback)((el) => {
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
            funcRef.current = (0, corner_smoothing_vanilla_1.squircleObserver)(el, opts);
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
    return (react_1.default.createElement(Component, { ref: refCallback, ...rest }, children));
});
/**
 * HOC that wraps `Component` and injects the squircle style.
 */
const squircle = (Component, opts) => (0, react_1.forwardRef)((props, forwardedRef) => {
    const funcRef = (0, react_1.useRef)();
    const refCallback = (0, react_1.useCallback)((el) => {
        funcRef.current?.disconnect();
        if (el) {
            funcRef.current = (0, corner_smoothing_vanilla_1.squircleObserver)(el, opts);
        }
        if (typeof forwardedRef === "function") {
            forwardedRef(el);
        }
        else if (forwardedRef) {
            forwardedRef.current = el;
        }
    }, [opts]);
    // @ts-ignore
    return react_1.default.createElement(Component, { ref: refCallback, ...props });
});
exports.squircle = squircle;
//# sourceMappingURL=corner-smoothing-react.js.map