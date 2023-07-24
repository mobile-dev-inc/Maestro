import React, { Fragment, useEffect, useMemo, useRef, useState } from "react";
import { Button } from "../design-system/button";
import { Input } from "../design-system/input";
import { DeviceScreen, UIElement } from "../../helpers/models";
import clsx from "clsx";
import Draggable from "react-draggable";

const compare = (a: string | undefined, b: string | undefined) => {
  if (!a) return b ? 1 : 0;
  if (!b) return -1;
  return a.localeCompare(b);
};

interface ElementsPanelProps {
  deviceScreen: DeviceScreen;
  onElementSelected: (element: UIElement | null) => void;
  hoveredElement: UIElement | null;
  setHoveredElement: (element: UIElement | null) => void;
  closePanel: () => void;
  onHint: (hint: string | null) => void;
}

export default function ElementsPanel({
  deviceScreen,
  onElementSelected,
  hoveredElement,
  setHoveredElement,
  closePanel,
  onHint,
}: ElementsPanelProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [query, setQuery] = useState<string>("");
  const [width, setWidth] = useState(
    localStorage.sidebarWidth ? parseInt(localStorage.sidebarWidth) : 264
  );
  const minWidth = 264;
  const maxWidth = 560;

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  useEffect(() => {
    return () => {
      localStorage.setItem("sidebarWidth", width.toString());
    };
  }, [width]);

  const handleDrag = (e: any, ui: any) => {
    let newWidth = width + ui.deltaX;
    if (newWidth < minWidth) {
      newWidth = minWidth;
    } else if (newWidth > maxWidth) {
      newWidth = maxWidth;
    }
    setWidth(newWidth);
  };

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  const sortedElements: UIElement[] = useMemo(() => {
    const filteredElements = deviceScreen.elements.filter((element) => {
      if (
        !element.text &&
        !element.resourceId &&
        !element.hintText &&
        !element.accessibilityText
      )
        return false;

      return (
        !query ||
        element.text?.toLowerCase().includes(query.toLowerCase()) ||
        element.resourceId?.toLowerCase().includes(query.toLowerCase()) ||
        element.hintText?.toLowerCase().includes(query.toLowerCase()) ||
        element.accessibilityText?.toLowerCase().includes(query.toLowerCase())
      );
    });

    return filteredElements.sort((a, b) => {
      const aTextPrefixMatch =
        query && a.text?.toLowerCase().startsWith(query.toLowerCase());
      const bTextPrefixMatch =
        query && b.text?.toLowerCase().startsWith(query.toLowerCase());

      if (aTextPrefixMatch && !bTextPrefixMatch) return -1;
      if (bTextPrefixMatch && !aTextPrefixMatch) return 1;

      return compare(a.text, b.text) || compare(a.resourceId, b.resourceId);
    });
  }, [query, deviceScreen.elements]);

  return (
    <div
      style={{
        width: width,
        minWidth: width,
        maxWidth: width,
      }}
      className="flex flex-col relative h-full overflow-visible z-10 border-r border-slate-200 dark:border-slate-800"
    >
      <Button
        onClick={closePanel}
        variant="tertiary"
        icon="RiCloseLine"
        className="rounded-full absolute top-6 -right-4 z-10"
      />
      <div className="px-8 py-6 border-b border-slate-200 dark:border-slate-800">
        <Input
          ref={inputRef}
          onChange={(e) => setQuery(e.target.value)}
          size="sm"
          leftIcon="RiSearchLine"
          leftIconClassName="absolute left-1.5 top-1/2 transform -translate-y-1/2 pointer-events-none"
          inputClassName="px-6"
          placeholder="Text or Id"
          className="relative w-full rounded-md p-0"
        />
      </div>
      <div className="px-8 py-6 flex-grow overflow-y-scroll overflow-x-hidden">
        {sortedElements.map((item: UIElement) => {
          const onClick = () => onElementSelected(item);
          const onMouseEnter = () => {
            setHoveredElement(item);
            onHint(item?.resourceId || item?.text || null);
          };
          const onMouseLeave = () => {
            onHint(null);
            if (hoveredElement?.id === item.id) {
              setHoveredElement(null);
            }
          };
          return (
            <Fragment key={item.id}>
              {item.resourceId !== "" && item.resourceId !== " " && (
                <ElementListItem
                  onClick={onClick}
                  onMouseEnter={onMouseEnter}
                  onMouseLeave={onMouseLeave}
                  isHovered={hoveredElement?.id === item?.id}
                  query={query as string}
                  text={item.resourceId as string}
                  elementType="id"
                />
              )}
              {item.text !== "" && item.text !== " " && (
                <ElementListItem
                  onClick={onClick}
                  isHovered={hoveredElement?.id === item?.id}
                  onMouseEnter={onMouseEnter}
                  onMouseLeave={onMouseLeave}
                  query={query as string}
                  text={item.text as string}
                  elementType="text"
                />
              )}
              {item.hintText !== "" && item.hintText !== " " && (
                <ElementListItem
                  onClick={onClick}
                  isHovered={hoveredElement?.id === item?.id}
                  onMouseEnter={onMouseEnter}
                  onMouseLeave={onMouseLeave}
                  query={query as string}
                  text={item.hintText as string}
                  elementType="hintText"
                />
              )}
              {item.accessibilityText !== "" &&
                item.accessibilityText !== " " && (
                  <ElementListItem
                    onClick={onClick}
                    isHovered={hoveredElement?.id === item?.id}
                    onMouseEnter={onMouseEnter}
                    onMouseLeave={onMouseLeave}
                    query={query as string}
                    text={item.accessibilityText as string}
                    elementType="accessibilityText"
                  />
                )}
            </Fragment>
          );
        })}
      </div>
      <Draggable axis="x" onDrag={handleDrag} position={{ x: 0, y: 0 }}>
        <div
          style={{
            cursor:
              (width === maxWidth && "w-resize") ||
              (width === minWidth && "e-resize") ||
              "ew-resize",
          }}
          className="w-2 absolute top-0 -right-1 bottom-0 "
        />
      </Draggable>
    </div>
  );
}

interface ElementListItemProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  query: string;
  text: string;
  elementType: "id" | "text" | "hintText" | "accessibilityText";
  isHovered: boolean;
}

const ElementListItem = ({
  query,
  text,
  elementType,
  isHovered,
  ...rest
}: ElementListItemProps) => {
  if (!text) {
    return null;
  }

  const regEx = new RegExp(`(${query.toString()})`, "gi");
  const textParts: string[] = text.split(regEx);

  return (
    <button
      className={clsx(
        "px-2 py-2 bg-transparent hover:bg-slate-100 dark:hover:bg-slate-800 rounded-md transition w-full text-sm font-bold text-left",
        isHovered && "text-blue-500"
      )}
      style={{ overflowWrap: "anywhere" }}
      {...rest}
    >
      {textParts.map((part, index) => (
        <>
          {index % 2 === 0 ? (
            <span>{part}</span>
          ) : (
            <span className="text-purple-500 dark:text-purple-400">
              {query}
            </span>
          )}
        </>
      ))}
      <span className="text-gray-400 whitespace-nowrap"> • {elementType}</span>
    </button>
  );
};
