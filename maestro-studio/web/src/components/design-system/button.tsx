import * as React from "react";
import { cva } from "class-variance-authority";
import { clsx } from "clsx";
import { twMerge } from "tailwind-merge";
import { Icon, IconList } from "./icon";
import { Spinner } from "./spinner";

const buttonVariants = cva(
  "inline-flex items-center border justify-center font-semibold transition hover:shadow-e2 hover:z-[1] focus-visible:outline-none focus-visible:ring-4 focus-visible:shadow-e2 focus-visible:ring-ring focus-visible:ring-offset-0 focus-visible:z-[2] disabled:cursor-not-allowed ring-offset-background disabled:shadow-none disabled:bg-gray-300 dark:disabled:bg-gray-300/10 disabled:border-base-em disabled:text-gray-400 dark:disabled:text-gray-600",
  {
    variants: {
      variant: {
        primary:
          "bg-purple-500 text-white border-transparent shadow-sm hover:bg-purple-400 focus-visible:bg-purple-400 focus-visible:ring-purple-100",
        "primary-blue":
          "bg-blue-500 text-white border-transparent shadow-sm hover:bg-blue-400 focus-visible:bg-blue-400 focus-visible:ring-blue-100",
        "primary-red":
          "bg-red-500 text-white border-transparent shadow-sm hover:bg-red-400 focus-visible:bg-red-400 focus-visible:ring-red-100",
        "primary-green":
          "bg-green-500 dark:bg-green-500/40 text-white border-transparent shadow-sm hover:bg-green-400 focus-visible:bg-green-400 focus-visible:ring-green-100",
        "primary-yellow":
          "bg-yellow-500 text-gray-400 border-transparent shadow-sm hover:bg-yellow-400 focus-visible:bg-yellow-400 focus-visible:ring-yellow-100",
        secondary:
          "bg-purple-25 dark:bg-purple-500/10 text-purple-500 dark:text-purple-200 border-purple-75 dark:border-purple-500/20 shadow-sm hover:bg-purple-50 dark:hover:bg-purple-500/20 hover:text-purple-500 dark:hover-text-200 focus-visible:bg-purple-50 dark:focus-visible:bg-purple-400/10 focus-visible:ring-purple-100 dark:focus-visible:ring-purple-100/10",
        "secondary-blue":
          "bg-blue-50 dark:bg-blue-50/10 text-blue-500 border-blue-200 dark:border-blue-200/20 shadow-sm hover:bg-blue-50 hover:text-blue-500 focus-visible:bg-blue-50 focus-visible:ring-blue-100",
        "secondary-green":
          "bg-green-50 dark:bg-green-50/10 text-green-500 border-green-200 dark:border-green-200/20 shadow-sm hover:bg-green-50 hover:text-green-500 focus-visible:bg-green-50 focus-visible:ring-green-100",
        "secondary-red":
          "bg-red-50 dark:bg-red-50/10 text-red-500 border-red-200 dark:border-red-200/20 shadow-sm hover:bg-red-50 hover:text-red-500 focus-visible:bg-red-50 focus-visible:ring-red-100",
        "secondary-yellow":
          "bg-yellow-50 dark:bg-yellow-50/10 text-yellow-500 border-yellow-200 dark:border-yellow-200/20 shadow-sm hover:bg-yellow-50 hover:text-yellow-500 focus-visible:bg-yellow-50 focus-visible:ring-yellow-100",
        tertiary:
          "bg-white dark:bg-slate-800 text-gray-700 dark:text-gray-200 border-slate-200 dark:border-slate-200/20 shadow-sm hover:bg-slate-50 dark:hover:bg-slate-50/10 focus-visible:bg-slate-50 dark:focus-visible:bg-slate-50/10 focus-visible:ring-slate-100 dark:focus-visible:ring-slate-100/20",
        "tertiary-blue":
          "bg-white dark:bg-slate-800 text-blue-500 dark:text-blue-200 border-blue-200 dark:border-blue-200/20 shadow-sm hover:bg-blue-50 dark:hover:bg-blue-50/10 focus-visible:bg-blue-50 dark:focus-visible:bg-blue-50/10 focus-visible:ring-blue-100 dark:focus-visible:ring-blue-100/20",
        "tertiary-green":
          "bg-white dark:bg-slate-800 text-green-500 dark:text-green-200 border-green-200 dark:border-green-200/20 shadow-sm hover:bg-green-50 dark:hover:bg-green-50/10 focus-visible:bg-green-50 dark:focus-visible:bg-green-50/10 focus-visible:ring-green-100 dark:focus-visible:ring-green-100/20",
        "tertiary-red":
          "bg-white dark:bg-slate-800 text-red-500 dark:text-red-200 border-red-200 dark:border-red-200/20 shadow-sm hover:bg-red-50 dark:hover:bg-red-50/10 focus-visible:bg-red-50 dark:focus-visible:bg-red-50/10 focus-visible:ring-red-100 dark:focus-visible:ring-red-100/20",
        "tertiary-yellow":
          "bg-white dark:bg-slate-800 text-yellow-500 dark:text-yellow-200 border-yellow-200 dark:border-yellow-200/20 shadow-sm hover:bg-yellow-50 focus-visible:bg-yellow-50 dark:focus-visible:bg-yellow-50/10 focus-visible:ring-yellow-100 dark:focus-visible:ring-yellow-100/20",
        quaternary:
          "bg-transparent text-gray-700 dark:text-white border-transparent hover:border-slate-200 dark:hover:border-slate-200/20 hover:bg-slate-50 dark:hover:bg-slate-50/10 focus-visible:border-slate-200 dark:focus-visible:border-slate-200/20 focus-visible:bg-slate-50 dark:focus-visible:bg-slate-50/10 focus-visible:ring-slate-100 dark:focus-visible:ring-slate-100/20",
        "quaternary-blue":
          "bg-transparent text-blue-500 border-transparent hover:border-blue-200 dark:hover:border-blue-200/20 hover:bg-blue-50 dark:hover:bg-blue-50/10 focus-visible:border-blue-200 dark:focus-visible:border-blue-200/20 focus-visible:bg-blue-50 dark:focus-visible:bg-blue-50/10 focus-visible:ring-blue-100 dark:focus-visible:ring-blue-100/20",
        "quaternary-green":
          "bg-transparent text-green-500 border-transparent hover:border-green-200 dark:hover:border-green-200/20 hover:bg-green-50 dark:hover:bg-green-50/10 focus-visible:border-green-200 dark:focus-visible:border-green-200/20 focus-visible:bg-green-50 dark:focus-visible:bg-green-50/10 focus-visible:ring-green-100 dark:focus-visible:ring-green-100/20",
        "quaternary-red":
          "bg-transparent text-red-500 border-transparent hover:border-red-200 dark:hover:border-red-200/20 hover:bg-red-50 dark:hover:bg-red-50/10 focus-visible:border-red-200 dark:focus-visible:border-red-200/20 focus-visible:bg-red-50 dark:focus-visible:bg-red-50/10 focus-visible:ring-red-100 dark:focus-visible:ring-red-100/20",
        "quaternary-yellow":
          "bg-transparent text-yellow-500 border-transparent hover:border-yellow-200 dark:hover:border-yellow-200/20 hover:bg-yellow-50 dark:hover:bg-yellow-50/10 focus-visible:border-yellow-200 dark:focus-visible:border-yellow-200/20 focus-visible:bg-yellow-50 dark:focus-visible:bg-yellow-50/10 focus-visible:ring-yellow-100 dark:focus-visible:ring-yellow-100/20",
      },
      size: {
        xs: "rounded-sm gap-px text-[10px] px-2 h-6",
        sm: "rounded-md gap-1.5 text-xs px-2.5 h-8",
        md: "rounded-md gap-2 text-sm px-3 h-10",
        lg: "rounded-lg gap-2.5 text-md px-4 h-12",
        xl: "rounded-lg gap-3 text-lg px-4 h-14",
      },
      iconExist: {
        false: "",
        xs: "w-6 min-w-6 max-w-6 h-6 px-0",
        sm: "w-8 min-w-8 max-w-8 h-8 px-0",
        md: "w-10 min-w-10 max-w-10 h-10 px-0",
        lg: "w-12 min-w-12 max-w-12 h-12 px-0",
        xl: "w-14 min-w-14 max-w-14 h-14 px-0",
      },
    },
    defaultVariants: {
      variant: "primary",
      size: "sm",
    },
  }
);

const getIconSize = (
  size: "xs" | "sm" | "md" | "lg" | "xl"
): "12" | "14" | "16" | "18" | "20" | "24" | "28" | "32" | undefined => {
  switch (size) {
    case "xs":
      return "12";
    case "sm":
      return "14";
    case "md":
      return "18";
    case "lg":
      return "20";
    case "xl":
      return "24";
    default:
      return undefined;
  }
};

interface CommonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?:
    | "primary"
    | "primary-blue"
    | "primary-red"
    | "primary-green"
    | "primary-yellow"
    | "secondary"
    | "secondary-blue"
    | "secondary-red"
    | "secondary-green"
    | "secondary-yellow"
    | "tertiary"
    | "tertiary-blue"
    | "tertiary-red"
    | "tertiary-green"
    | "tertiary-yellow"
    | "quaternary"
    | "quaternary-blue"
    | "quaternary-red"
    | "quaternary-green"
    | "quaternary-yellow";
  size?: "xs" | "sm" | "md" | "lg" | "xl";
  isLoading?: boolean;
}

type ConditionalProps =
  | {
      icon?: keyof typeof IconList;
      iconClassName?: string;
      iconElement?: React.ReactNode;
      leftIcon?: never;
      leftIconClassName?: never;
      rightIcon?: never;
      rightIconClassName?: string;
      children?: never;
    }
  | {
      icon?: never;
      iconClassName?: never;
      iconElement?: never;
      leftIcon?: keyof typeof IconList;
      leftIconClassName?: string;
      rightIcon?: keyof typeof IconList;
      rightIconClassName?: string;
      children?: React.ReactNode;
    };

export type ButtonProps = CommonProps & ConditionalProps;

interface ButtonGroupProps extends React.ComponentPropsWithoutRef<"div"> {}

const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  (
    {
      className,
      variant = "primary",
      size = "sm",
      icon,
      iconElement,
      iconClassName,
      leftIcon,
      leftIconClassName,
      rightIcon,
      rightIconClassName,
      isLoading = false,
      children,
      ...props
    },
    ref
  ) => {
    return (
      <button
        className={twMerge(
          clsx(
            buttonVariants({
              variant,
              size,
              iconExist: icon || iconElement ? size : false,
              className,
            })
          )
        )}
        ref={ref}
        {...props}
      >
        {iconElement ||
          (icon ? (
            isLoading ? (
              <Spinner size={size && getIconSize(size)} />
            ) : (
              <Icon
                iconName={icon}
                size={size && getIconSize(size)}
                className={iconClassName}
              />
            )
          ) : (
            <>
              {isLoading && <Spinner size={size && getIconSize(size)} />}
              {leftIcon && (
                <Icon
                  iconName={leftIcon}
                  size={size && getIconSize(size)}
                  className={leftIconClassName}
                />
              )}
              {children}
              {rightIcon && (
                <Icon
                  iconName={rightIcon}
                  size={size && getIconSize(size)}
                  className={rightIconClassName}
                />
              )}
            </>
          ))}
      </button>
    );
  }
);
Button.displayName = "Button";

const ButtonGroup = React.forwardRef<HTMLDivElement, ButtonGroupProps>(
  ({ className, ...props }, ref) => {
    const gapClasses =
      className &&
      className
        .split(" ")
        .some((className: string) => className.startsWith("gap-"));
    return (
      <div
        ref={ref}
        className={twMerge(
          clsx(
            "flex items-center",
            gapClasses
              ? null
              : "[&>button:not(:first-child)]:ml-[-1px] [&>button:not(:first-child)]:rounded-l-[0] [&>button:not(:last-child)]:rounded-r-[0]",
            className
          )
        )}
        {...props}
      />
    );
  }
);
ButtonGroup.displayName = "ButtonGroup";

export { Button, ButtonGroup, buttonVariants };
