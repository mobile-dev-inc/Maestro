import React, {
  HtmlHTMLAttributes,
  InputHTMLAttributes,
  LabelHTMLAttributes,
  ReactNode,
  TextareaHTMLAttributes,
  forwardRef,
} from "react";
import clsx from "clsx";
import { cva } from "class-variance-authority";
import { Icon, IconList } from "./icon";
import { twMerge } from "tailwind-merge";
import { TextAreaResizer } from "./utils/images";

const labelVariant = cva("font-semibold text-med-em", {
  variants: {
    size: {
      sm: "text-xs pb-1 pl-1",
      md: "text-xs pb-1 pl-1",
      lg: "text-sm pb-1 pl-1",
      xl: "text-base pb-1.5 pl-1",
    },
    disabled: {
      true: "text-gray-400",
      false: "",
    },
  },
});

const inputVariants = cva(
  "flex transition cursor-text w-full text-gray-900 dark:text-white items-center border border-slate-200 dark:border-slate-700 justify-center font-semibold transition ring-4 focus-within:shadow-e2 ring-ring ring-offset-0 ring-transparent focus-within:ring-purple-100 dark:focus-within:ring-purple-100/20 focus-within:border-purple-500 dark:focus-within:border-purple-500 ring-offset-background",
  {
    variants: {
      size: {
        sm: "rounded-md gap-1.5 text-xs px-2 h-8",
        md: "rounded-md gap-2 text-sm px-3 h-10",
        lg: "rounded-lg gap-2.5 text-base px-4 h-12",
        xl: "rounded-lg gap-3 text-lg px-4 h-14",
      },
      success: {
        true: "text-green-500 ring-green-100 border-green-400 focus-within:ring-green-100 focus-within:border-green-400",
        false: "",
      },
      error: {
        true: "text-red-500 ring-red-100 dark:ring-red-100/20 dark:text-red-500 border-red-400 hover:border-red-400 dark:border-red-400 dark:hover:border-red-400 focus:ring-red-100 dark:focus:ring-red-100/20 focus:border-red-400 focus:hover:border-red-400",
        false: "",
      },
      disabled: {
        true: "cursor-not-allowed bg-gray-75 text-disabled",
        false: "",
      },
    },
  }
);

const textareaVariants = cva(
  "block transition bg-white dark:bg-slate-800/50 cursor-text w-full text-gray-900 dark:text-white border border-slate-200 dark:border-slate-700 justify-center font-semibold transition ring-4 focus:outline-none focus:shadow-e2 ring-ring ring-offset-0 ring-transparent focus:ring-purple-100 dark:focus:ring-purple-100/20 focus:border-purple-500 dark:focus:border-purple-500 focus:hover:border-purple-500 ring-offset-background disabled:cursor-not-allowed disabled:bg-gray-100 disabled:text-disabled disabled:hover:border-slate-300",
  {
    variants: {
      size: {
        sm: "rounded-sm text-xs px-2.5 py-2",
        md: "rounded-md text-sm px-3 py-2",
        lg: "rounded-lg text-base px-4 py-3",
        xl: "rounded-lg text-lg px-4 py-3",
      },
      success: {
        true: "text-green-500 ring-success-100 border-green-400 hover:border-green-400 focus:ring-success-100 focus:border-green-400 focus:hover:border-green-400",
        false: "",
      },
      error: {
        true: "text-red-500 ring-red-100 dark:ring-red-100/20 dark:text-red-500 border-red-400 hover:border-red-400 dark:border-red-400 dark:hover:border-red-400 focus:ring-red-100 dark:focus:ring-red-100/20 focus:border-red-400 focus:hover:border-red-400",
        false: "",
      },
    },
  }
);

const inputHintVariant = cva(
  "flex w-full items-center gap-8 text-med-em font-semibold",
  {
    variants: {
      size: {
        sm: "gap-1 text-[10px] pl-1 pt-0.5",
        md: "gap-1 text-xs pl-1 pt-0.5",
        lg: "gap-1 text-sm pl-1 pt-0.5",
        xl: "gap-1 text-base pl-1 pt-1.5",
      },
      success: {
        true: "text-green-500",
        false: "",
      },
      error: {
        true: "text-red-500",
        false: "",
      },
      disabled: {
        true: "text-gray-400",
        false: "",
      },
    },
  }
);

const getIconSize = (
  size: "sm" | "md" | "lg" | "xl"
): "12" | "14" | "16" | "18" | "20" | "24" | "28" | "32" | undefined => {
  switch (size) {
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

const getHintIconSize = (
  size: "sm" | "md" | "lg" | "xl"
): "12" | "14" | "16" | "18" | "20" | "24" | "28" | "32" | undefined => {
  switch (size) {
    case "sm":
      return "12";
    case "md":
      return "14";
    case "lg":
      return "14";
    case "xl":
      return "18";
    default:
      return undefined;
  }
};

interface InputWrapperProps extends LabelHTMLAttributes<HTMLLabelElement> {
  size?: "sm" | "md" | "lg" | "xl";
  disabled?: boolean;
  success?: boolean | string | null;
  error?: boolean | ReactNode | string | null;
}

interface InpurLabelProps extends HtmlHTMLAttributes<HTMLElement> {
  text?: string;
  size?: "sm" | "md" | "lg" | "xl";
  labelHint?: string;
  disabled?: boolean;
}

interface InputProps
  extends Omit<InputHTMLAttributes<HTMLInputElement>, "size"> {
  size?: "sm" | "md" | "lg" | "xl";
  leftElement?: React.ReactNode;
  leftIcon?: keyof typeof IconList;
  leftIconClassName?: string;
  rightElement?: React.ReactNode;
  rightIcon?: keyof typeof IconList;
  rightIconClassName?: string;
  success?: boolean | string | null;
  error?: boolean | ReactNode | string | null;
  inputClassName?: string;
}

interface TextareaProps
  extends Omit<TextareaHTMLAttributes<HTMLTextAreaElement>, "size"> {
  size?: "sm" | "md" | "lg" | "xl";
  success?: boolean | string | null;
  error?: boolean | ReactNode | string | null;
  resize?: "automatic" | "vertical" | "none";
  showResizeIcon?: boolean;
  textAreaClassName?: string;
}

interface InputHintProps extends HtmlHTMLAttributes<HTMLElement> {
  size?: "sm" | "md" | "lg" | "xl";
  icon?: keyof typeof IconList;
  disabled?: boolean;
  hint?: string;
  success?: boolean | string | null;
  error?: boolean | ReactNode | string | null;
}

function InputWrapper({
  size = "md",
  disabled,
  children,
  success,
  error,
  className,
  ...rest
}: InputWrapperProps) {
  const childrenWithProps = React.Children.map(children, (child) => {
    if (React.isValidElement<InputProps>(child) && child.type === Input) {
      return React.cloneElement(child, { size, disabled, success, error });
    }
    if (React.isValidElement<TextareaProps>(child) && child.type === TextArea) {
      return React.cloneElement(child, { size, disabled, success, error });
    }
    if (
      React.isValidElement<InpurLabelProps>(child) &&
      child.type === InputLabel
    ) {
      return React.cloneElement(child, { size, disabled });
    }
    if (
      React.isValidElement<InputHintProps>(child) &&
      child.type === InputHint
    ) {
      return React.cloneElement(child, { size, disabled, success, error });
    }
    return child;
  });

  return (
    <label
      {...rest}
      className={twMerge(clsx(disabled && "cursor-not-allowed", className))}
    >
      {childrenWithProps}
    </label>
  );
}

function InputLabel({
  size,
  text,
  labelHint,
  className,
  disabled,
  ...rest
}: InpurLabelProps) {
  if (!labelHint) {
    return (
      <p
        className={twMerge(clsx(labelVariant({ size, disabled, className })))}
        {...rest}
      >
        {text}
      </p>
    );
  }

  return (
    <p
      className={twMerge(
        clsx(
          labelVariant({
            size,
            disabled,
            className: className,
          })
        )
      )}
      {...rest}
    >
      {text}
    </p>
  );
}

const Input = forwardRef(
  (
    {
      size = "md",
      leftElement,
      leftIcon,
      leftIconClassName,
      rightElement,
      rightIcon,
      rightIconClassName,
      success,
      error,
      className,
      disabled,
      inputClassName,
      ...rest
    }: InputProps,
    ref: React.ForwardedRef<HTMLInputElement>
  ) => {
    return (
      <>
        <div
          className={twMerge(
            clsx(
              inputVariants({
                size,
                success: !!success,
                error: !!error,
                disabled,
                className,
              })
            )
          )}
        >
          {leftElement}
          {leftIcon && (
            <Icon
              iconName={leftIcon as keyof typeof IconList}
              size={size && getIconSize(size)}
              className={twMerge(
                clsx(
                  disabled
                    ? "text-gray-400"
                    : "text-gray-800 dark:text-white/80",
                  leftIconClassName
                )
              )}
            />
          )}
          <input
            ref={ref}
            className={clsx(
              twMerge(
                "flex-grow border-none bg-transparent placeholder:text-gray-400 autofill:shadow-[0_0_0_30px_white_inset_!important] focus:outline-none disabled:cursor-not-allowed",
                inputClassName
              )
            )}
            disabled={disabled}
            {...rest}
          />
          {rightIcon && (
            <Icon
              iconName={rightIcon as keyof typeof IconList}
              size={size && getIconSize(size)}
              className={twMerge(
                clsx(
                  disabled
                    ? "text-gray-400"
                    : "text-gray-800 dark:text-white/80",
                  rightIconClassName
                )
              )}
            />
          )}
          {rightElement}
        </div>
      </>
    );
  }
);

const TextArea = React.forwardRef<HTMLTextAreaElement, TextareaProps>(
  (
    {
      size = "md",
      success,
      error,
      className,
      textAreaClassName,
      resize,
      onChange,
      showResizeIcon = true,
      ...rest
    },
    ref?: Exclude<React.Ref<HTMLTextAreaElement>, string>
  ) => {
    const textAreaRef = React.useRef<HTMLTextAreaElement>(null);

    // Use the forwarded ref if provided, otherwise use local ref
    React.useImperativeHandle(ref, () => {
      if (textAreaRef.current) {
        return textAreaRef.current;
      } else {
        throw new Error("TextArea ref is not yet available.");
      }
    });

    React.useEffect(() => {
      if (
        resize === "automatic" &&
        typeof textAreaRef !== "function" &&
        textAreaRef.current
      ) {
        textAreaRef.current.style.height = "auto";
        textAreaRef.current.style.height = `${
          textAreaRef.current.scrollHeight + 2
        }px`;
      }
    }, [textAreaRef, resize, rest.value]);

    const handleEvent = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
      const target = e.target as HTMLTextAreaElement;

      if (resize === "automatic" && target) {
        target.style.height = "auto";
        target.style.height = `${target.scrollHeight + 2}px`;
      }

      if (e.type === "change" && onChange) {
        onChange(e as React.ChangeEvent<HTMLTextAreaElement>);
      }
    };

    return (
      <div className={twMerge(clsx("relative", className))}>
        <textarea
          ref={textAreaRef}
          className={twMerge(
            clsx(
              textareaVariants({
                size,
                success: !!success,
                error: !!error,
                className: clsx(
                  (resize === "none" || resize === "automatic") &&
                    "resize-none",
                  textAreaClassName
                ),
              })
            )
          )}
          onChange={handleEvent}
          {...rest}
        />
        {resize !== "none" && showResizeIcon && (
          <TextAreaResizer className="pointer-events-none absolute bottom-0.5 right-0.5 h-4 w-4 text-gray-600" />
        )}
      </div>
    );
  }
);

function InputHint({
  hint,
  disabled,
  size = "md",
  icon,
  className,
  success,
  error,
  ...rest
}: InputHintProps) {
  if (
    hint ||
    typeof success === "string" ||
    (error && typeof error !== "boolean")
  ) {
    let hintText: string | ReactNode | undefined = hint;
    typeof success === "string" && (hintText = success);
    error && typeof error !== "boolean" && (hintText = error);
    return (
      <div
        className={twMerge(
          clsx(
            inputHintVariant({
              size,
              success: !!success,
              error: !!error,
              disabled,
              className,
            })
          )
        )}
        {...rest}
      >
        <Icon
          iconName="RiInformationFill"
          size={size && getHintIconSize(size)}
        />
        {hintText}
      </div>
    );
  }

  return null;
}

export { Input, TextArea, InputLabel, InputWrapper, InputHint };
