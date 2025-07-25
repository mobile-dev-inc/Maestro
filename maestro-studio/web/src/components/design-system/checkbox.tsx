import * as React from "react";
import { clsx } from "clsx";
import { twMerge } from "tailwind-merge";
import { CheckboxCheck, CheckboxIntermediate } from "./utils/images";

const checkboxStates = {
  indeterminate: [
    "[&>input:indeterminate~.checkmark>.icon-wrapper]:bg-purple-300 dark:[&>input:indeterminate~.checkmark>.icon-wrapper]:bg-purple-500/50", // Checkmark bg color
    "[&>input:indeterminate~.checkmark>.icon-wrapper]:border-none", // Checkmark no border
    "[&>input:indeterminate~.checkmark>.icon-wrapper>.indeterminate-icon]:block", // Show indeterminate icon
  ],
  checked: [
    "[&>input:checked~.checkmark>.icon-wrapper]:bg-purple-500", // Checkmark bg color
    "[&>input:checked~.checkmark>.icon-wrapper]:border-none", // Checkmark no border
    "[&>input:checked~.checkmark>.icon-wrapper>.checkmark-tick]:block", // Show check icon
  ],
  focus: [
    "[&>input:focus-visible~.checkmark>.icon-wrapper]:ring-4",
    "[&>input:focus-visible~.checkmark>.icon-wrapper]:ring-ring",
    "[&>input:focus-visible~.checkmark>.icon-wrapper]:ring-gray-100 dark:[&>input:focus-visible~.checkmark>.icon-wrapper]:ring-gray-100/10", // Focus shadow when unchecked
    "[&>input:focus-visible:checked~.checkmark>.icon-wrapper]:ring-purple-100 dark:[&>input:focus-visible:checked~.checkmark>.icon-wrapper]:ring-purple-100/10", // Focus shadow when checked
    "[&>input:focus:indeterminate~.checkmark>.icon-wrapper]:ring-purple-100 dark:[&>input:focus:indeterminate~.checkmark>.icon-wrapper]:ring-purple-100/10", // Focus shadow when indeterminate
  ],
  disabled: [
    '[&>input[type="checkbox"]:disabled~.checkmark>.icon-wrapper]:border-gray-200', // Checkmark border when unchecked
    '[&>input[type="checkbox"]:disabled:checked~.checkmark>.icon-wrapper]:bg-gray-200', // Checkmark bg when checked
    '[&>input[type="checkbox"]:disabled:checked~.checkmark>.icon-wrapper]:text-med-em', // Checkmark bg when checked
    '[&>input[type="checkbox"]:disabled:indeterminate~.checkmark>.icon-wrapper]:bg-gray-400', // Checkmark bg when indeterminate
  ],
};

interface CheckboxProps
  extends Omit<
    React.InputHTMLAttributes<HTMLInputElement>,
    "size" | "onChange"
  > {
  size?: "sm" | "md";
  shape?: "square" | "round";
  label?: string;
  caption?: string;
  indeterminate?: boolean;
  disabled?: boolean;
  onChange?: (checked: boolean) => void;
}

const Checkbox = ({
  size = "md",
  shape = "square",
  label,
  caption,
  className = "",
  checked,
  disabled,
  indeterminate,
  defaultChecked,
  onChange,
  ...rest
}: CheckboxProps) => {
  return (
    <label
      className={twMerge(
        clsx(
          "flex gap-3",
          disabled ? "cursor-not-allowed" : "cursor-pointer",
          ...checkboxStates["indeterminate"],
          ...checkboxStates["checked"],
          ...checkboxStates["focus"],
          ...checkboxStates["disabled"],
          className
        )
      )}
    >
      <input
        ref={(input) => {
          if (input) {
            defaultChecked && (input.checked = true);
            indeterminate && (input.indeterminate = true);
            !indeterminate && (input.indeterminate = false);
          }
        }}
        type="checkbox"
        className="h-0 w-0 absolute opacity-0 pointer-events-none"
        onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
          onChange && onChange(e.target.checked)
        }
        checked={checked}
        disabled={disabled}
        {...rest}
      />
      <div
        className={clsx(
          "checkmark",
          size === "sm" && "p-0.5",
          size === "md" && "p-[3px]",
          !disabled && "text-white",
          disabled && indeterminate && "text-med-em",
          disabled && !indeterminate && "text-disabled"
        )}
      >
        <div
          className={clsx(
            "icon-wrapper",
            size === "sm" && "h-4 w-4 min-w-4",
            size === "md" && "h-[18px] w-[18px] min-w-[18px]",
            "border-2",
            disabled ? "border-gray-200" : "border-gray-300",
            shape === "round" ? "rounded-full" : "rounded-md"
          )}
        >
          {indeterminate && (
            <CheckboxIntermediate className="indeterminate-icon hidden w-full h-full" />
          )}
          <CheckboxCheck className="checkmark-tick hidden w-full h-full" />
        </div>
      </div>
      {(label || caption) && (
        <div className="flex flex-col gap-0.5 justify-center flex-grow">
          {label && (
            <p
              className={clsx(
                "font-semibold",
                size === "md" ? "text-sm" : "text-xs",
                disabled && indeterminate && "text-current",
                disabled && !indeterminate && "text-gray-300"
              )}
            >
              {label}
            </p>
          )}
          {caption && (
            <p
              className={clsx(
                "text-xs",
                disabled ? "text-disabled" : "text-gray-300"
              )}
            >
              {caption}
            </p>
          )}
        </div>
      )}
    </label>
  );
};

export { Checkbox };
