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

const CommandInput = ({
  value,
  setValue,
  error,
  resize = "automatic",
  onSubmit,
  className,
  ...rest
}: CommandInputProps & TextAreaProps) => {
  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
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
        id="commandInputBox"
        value={value}
        rows={2}
        resize={resize}
        showResizeIcon={false}
        onChange={(e) => {
          setValue(e.target.value);
        }}
        onKeyDown={handleKeyDown}
        className={twMerge(clsx("font-mono font-normal", className))}
        {...rest}
      />
      <InputHint />
    </InputWrapper>
  );
};

export default CommandInput;
