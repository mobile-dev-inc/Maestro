import * as React from "react";
import { cva } from "class-variance-authority";
import { clsx } from "clsx";
import { twMerge } from "tailwind-merge";
import { Icon, IconList } from "./icon";

const linkVariant = cva(
  "inline-flex items-center font-semibold border-b-2 border-transparent transition focus-visible:outline-none focus-visible:ring-4 focus-visible:shadow-e2 focus-visible:ring-ring focus-visible:ring-offset-0 ring-offset-background",
  {
    variants: {
      variant: {
        primary:
          "text-purple-500 hover:border-purple-200 dark:hover:border-purple-200/20 focus-visible:ring-purple-100 dark:focus-visible:ring-purple-100/20",
        info: "text-blue-500 hover:border-blue-200 dark:hover:border-blue-200/20 focus-visible:ring-blue-100 dark:focus-visible:ring-blue-100/20",
        success:
          "text-green-500 hover:border-green-200 dark:hover:green-blue-200/20 focus-visible:ring-green-100 dark:focus-visible:ring-green-100/20",
        danger:
          "text-red-500 hover:border-red-200 dark:hover:border-red-200/20 focus-visible:ring-red-100 dark:focus-visible:ring-red-100/20",
        warning:
          "text-yellow-500 hover:border-yellow-200 dark:hover:border-yellow-200/20 focus-visible:ring-yellow-100 dark:focus-visible:ring-yellow-100/20",
      },
      size: {
        xs: "gap-0.5 text-[10px]",
        sm: "gap-1 text-xs",
        md: "gap-1 text-sm",
        lg: "gap-1.5 text-base",
        xl: "gap-1.5 text-lg",
      },
      disabled: {
        true: "cursor-not-allowed text-disabled hover:border-transparent",
        false: "",
      },
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
      return "20";
    default:
      return undefined;
  }
};

type AnchorProps = React.DetailedHTMLProps<
  React.AnchorHTMLAttributes<HTMLAnchorElement>,
  HTMLAnchorElement
>;
type ButtonProps = React.DetailedHTMLProps<
  React.ButtonHTMLAttributes<HTMLButtonElement>,
  HTMLButtonElement
>;
type DivProps = React.DetailedHTMLProps<
  React.HTMLAttributes<HTMLDivElement>,
  HTMLDivElement
>;

type CustomProps =
  | (AnchorProps & { href: string })
  | (ButtonProps & { onClick: () => void })
  | DivProps;

interface CommonProps extends React.HtmlHTMLAttributes<HTMLElement> {
  variant?: "primary" | "info" | "danger" | "success" | "warning";
  size?: "xs" | "sm" | "md" | "lg" | "xl";
  leftIcon?: keyof typeof IconList;
  leftIconClassName?: string;
  rightIcon?: keyof typeof IconList;
  rightIconClassName?: string;
  disabled?: boolean;
}

type TagProps = CustomProps & CommonProps;

const Link = React.forwardRef<
  HTMLDivElement | HTMLButtonElement | HTMLAnchorElement,
  TagProps
>(
  (
    {
      className,
      variant = "primary",
      size = "md",
      leftIcon,
      leftIconClassName,
      rightIcon,
      rightIconClassName,
      disabled,
      children,
      ...props
    },
    ref
  ) => {
    /**
     * Setting up the element
     */
    let Element: "a" | "button" | "div";
    if ("href" in props) {
      Element = "a";
    } else if ("onClick" in props) {
      Element = "button";
    } else {
      Element = "div";
    }
    const Component = Element as any;

    return (
      <Component
        className={twMerge(
          clsx(
            linkVariant({
              variant,
              size,
              disabled: disabled || Element === "div",
              className,
            })
          )
        )}
        disabled={disabled}
        ref={ref as any}
        {...props}
      >
        {leftIcon && (
          <Icon
            iconName={leftIcon}
            size={getIconSize(size)}
            className={leftIconClassName}
          />
        )}
        {children}
        {rightIcon && (
          <Icon
            iconName={rightIcon}
            size={getIconSize(size)}
            className={rightIconClassName}
          />
        )}
      </Component>
    );
  }
);
Link.displayName = "Link";

export { Link };
