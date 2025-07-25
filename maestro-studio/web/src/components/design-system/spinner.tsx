import React from "react";
import { VariantProps, cva } from "class-variance-authority";
import { twMerge } from "tailwind-merge";
import clsx from "clsx";

const spinnerVariants = cva("", {
  variants: {
    size: {
      "12": "h-3 w-3 min-w-3",
      "14": "h-3.5 w-3.5 min-w-3.5",
      "16": "h-4 w-4 min-w-4",
      "18": "h-[18px] w-[18px] min-w-[18px]",
      "20": "h-5 w-5 min-w-5",
      "24": "h-6 w-6 min-w-6",
      "28": "h-7 w-7 min-w-7",
      "32": "h-8 w-8 min-w-8",
    },
  },
  defaultVariants: {
    size: "24",
  },
});

const strokeClasses = cva("", {
  variants: {
    size: {
      "12": "stroke-2",
      "14": "stroke-2",
      "16": "stroke-2",
      "18": "stroke-2",
      "20": "stroke-2",
      "24": "stroke-2",
      "28": "stroke-2",
      "32": "stroke-2",
    },
  },
  defaultVariants: {
    size: "24",
  },
});

export interface SpinnerProps
  extends React.SVGAttributes<SVGElement>,
    VariantProps<typeof spinnerVariants> {}

function Spinner({ className, size, ...props }: SpinnerProps) {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      width="38"
      height="38"
      viewBox="0 0 38 38"
      className={twMerge(clsx(spinnerVariants({ size, className })))}
      {...props}
    >
      <defs>
        <linearGradient x1="8.042%" y1="0%" x2="65.682%" y2="23.865%" id="a">
          <stop stopColor="currentColor" stop-opacity="0" offset="0%" />
          <stop stopColor="currentColor" stopOpacity=".631" offset="63.146%" />
          <stop stop-color="currentColor" offset="100%" />
        </linearGradient>
      </defs>
      <g fill="none" fill-rule="evenodd">
        <g transform="translate(1 1)">
          <path
            d="M36 18c0-9.94-8.06-18-18-18"
            id="Oval-2"
            stroke="url(#a)"
            strokeWidth="2"
            className={twMerge(clsx(strokeClasses({ size })))}
          >
            <animateTransform
              attributeName="transform"
              type="rotate"
              from="0 18 18"
              to="360 18 18"
              dur="0.5s"
              repeatCount="indefinite"
            />
          </path>
          <circle fill="currentColor" cx="36" cy="18" r="1">
            <animateTransform
              attributeName="transform"
              type="rotate"
              from="0 18 18"
              to="360 18 18"
              dur="0.5s"
              repeatCount="indefinite"
            />
          </circle>
        </g>
      </g>
    </svg>
  );
}

export { Spinner, spinnerVariants };
