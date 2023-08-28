import { ForwardRefRenderFunction, KeyboardEvent, forwardRef } from "react";
import { InputHint, InputWrapper, TextArea } from "../design-system/input";
import { TextAreaProps } from "../../helpers/models";
import { twMerge } from "tailwind-merge";
import clsx from "clsx";

interface CommandInputProps {
  value: string;
  setValue: (value: string) => void;
  resize?: "automatic" | "vertical" | "none";
  error?: boolean | string | null;
  onSubmit?: () => void;
}

type CombinedProps = CommandInputProps &
  TextAreaProps &
  React.RefAttributes<HTMLTextAreaElement>;

const CommandInput: ForwardRefRenderFunction<
  HTMLTextAreaElement,
  CombinedProps
> = (
  {
    value,
    setValue,
    error,
    resize = "automatic",
    onSubmit,
    className,
    ...rest
  },
  ref
) => {
  const handleKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.metaKey && e.key === "Enter") {
      onSubmit && onSubmit();
      return;
    } else if (e.key === "Tab") {
      e.preventDefault();
      const start = e.currentTarget.selectionStart;
      setValue(
        value.slice(0, start) +
          "    " +
          value.slice(e.currentTarget.selectionEnd)
      );
    }
  };

  return (
    <InputWrapper error={error}>
      <TextArea
        ref={ref}
        id="commandInputBox"
        value={value}
        rows={2}
        onChange={(e) => {
          setValue(e.target.value);
        }}
        resize={resize}
        showResizeIcon={false}
        onKeyDown={handleKeyDown}
        textAreaClassName={twMerge(
          clsx("font-mono font-normal pb-12", className)
        )}
        {...rest}
      />
      <InputHint />
    </InputWrapper>
  );
};

export default forwardRef(CommandInput);
