import { TextAreaProps } from './models';
import { useEffect, useRef } from 'react';

const AutosizingTextArea = ({value, setValue, ...rest}: {
  value: string
  setValue: (value: string) => void
} & TextAreaProps) => {
  const textAreaRef = useRef<HTMLTextAreaElement>(null);
  useEffect(() => {
    if (textAreaRef.current) {
      textAreaRef.current.style.height = "0px";
      const scrollHeight = textAreaRef.current.scrollHeight;
      textAreaRef.current.style.height = scrollHeight + "px";
      textAreaRef.current.focus()
    }
  }, [textAreaRef, value]);
  return (
    <textarea
      {...rest}
      ref={textAreaRef}
      onChange={e => {
        setValue(e.target.value)
      }}
      value={value}
    />
  )
}

export default AutosizingTextArea
