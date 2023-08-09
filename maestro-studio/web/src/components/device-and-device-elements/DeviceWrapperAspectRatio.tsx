import { useEffect, useRef, useState, ReactNode } from "react";

interface AspectRatioContainerProps
  extends React.HTMLAttributes<HTMLDivElement> {
  aspectRatio: number;
  children: ReactNode;
}

/**
 * This component is for make it work on different browsers.
 * Safari doesnt change aspect ratio even with max width, which cause overflow
 * So created this wrapper div that will calculate the width dynamically and then we can place aspect ratio inside it
 */
const DeviceWrapperAspectRatio = ({
  aspectRatio,
  children,
  ...rest
}: AspectRatioContainerProps) => {
  const [width, setWidth] = useState<number>(0);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const updateWidth = () => {
      if (containerRef.current) {
        const { height } = containerRef.current.getBoundingClientRect();
        const newWidth = height * aspectRatio;
        setWidth(newWidth);
      }
    };
    updateWidth();
    window.addEventListener("resize", updateWidth);
    return () => {
      window.removeEventListener("resize", updateWidth);
    };
  }, [aspectRatio]);

  return (
    <div ref={containerRef} className="relative flex-1">
      <div
        className="h-full max-w-full absolute top-0 left-0 right-0 bottom-0 mx-auto grid place-items-center"
        style={{ width }}
        {...rest}
      >
        {children}
      </div>
    </div>
  );
};

export default DeviceWrapperAspectRatio;
