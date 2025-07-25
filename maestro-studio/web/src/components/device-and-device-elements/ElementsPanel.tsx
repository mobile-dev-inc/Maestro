import React, { useEffect, useMemo, useRef, useState } from "react";
import _ from "lodash";
import { Button } from "../design-system/button";
import { Input } from "../design-system/input";
import { UIElement } from "../../helpers/models";
import clsx from "clsx";
import Draggable from "react-draggable";
import { useDeviceContext } from "../../context/DeviceContext";

const compare = (a: string | undefined, b: string | undefined) => {
  if (!a) return b ? 1 : 0;
  if (!b) return -1;
  return a.localeCompare(b);
};

interface ElementsPanelProps {
  closePanel: () => void;
}

export default function ElementsPanel({ closePanel }: ElementsPanelProps) {
  const {
    deviceScreen,
    hoveredElement,
    setHoveredElement,
    setInspectedElement,
    setFooterHint,
  } = useDeviceContext();
  const inputRef = useRef<HTMLInputElement>(null);
  const previousSortedElementsRef = useRef<UIElement[] | null>(null);
  const elementRefs = useRef<(HTMLElement | null)[]>([]);
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
    if (!deviceScreen) {
      return [];
    }
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
  }, [query, deviceScreen]);

  /**
   * Change hovered element in case sortedElements chang
   */
  useEffect(() => {
    // Check if the contents of sortedElements have changed
    const didElementsChange = !_.isEqual(
      previousSortedElementsRef.current,
      sortedElements
    );

    if (didElementsChange) {
      setHoveredElement(sortedElements[0]);
    }

    // Update the ref with the current value for the next comparison
    previousSortedElementsRef.current = sortedElements;
  }, [sortedElements, setHoveredElement]);

  /**
   * Keyboard Events
   */
  const handleKeyDown = (event: React.KeyboardEvent<HTMLInputElement>) => {
    const currentIndex = hoveredElement
      ? sortedElements.findIndex((el) => el.id === hoveredElement.id)
      : -1;

    let newIndex = -1;
    switch (event.key) {
      case "ArrowDown":
        event.preventDefault();
        newIndex =
          currentIndex + 1 >= sortedElements.length ? 0 : currentIndex + 1;
        setHoveredElement(sortedElements[newIndex]);
        break;
      case "ArrowUp":
        event.preventDefault();
        newIndex =
          currentIndex <= 0 ? sortedElements.length - 1 : currentIndex - 1;
        setHoveredElement(sortedElements[newIndex]);
        break;
      case "Enter":
        event.preventDefault();
        if (hoveredElement) {
          setInspectedElement(hoveredElement);
        }
        return; // Add a return here to avoid scrolling on Enter
      default:
        break;
    }
    // Scroll the new hovered element into view
    elementRefs.current[newIndex]?.scrollIntoView({
      behavior: "smooth",
      block: "nearest",
    });
  };

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
          onKeyDown={handleKeyDown}
          size="sm"
          leftIcon="RiSearchLine"
          leftIconClassName="absolute left-1.5 top-1/2 transform -translate-y-1/2 pointer-events-none"
          inputClassName="px-6"
          placeholder="Text or Id"
          className="relative w-full rounded-md p-0"
        />
      </div>
      <div className="px-8 py-6 flex-grow overflow-y-scroll overflow-x-hidden hide-scrollbar">
        {sortedElements.map((item: UIElement, index: number) => {
          const onClick = () => setInspectedElement(item);
          const onMouseEnter = () => {
            setHoveredElement(item);
            setFooterHint(item?.resourceId || item?.text || null);
          };
          const onMouseLeave = () => {
            setFooterHint(null);
            if (hoveredElement?.id === item.id) {
              setHoveredElement(null);
            }
          };
          return (
            <div
              key={item.id}
              ref={(ref) => (elementRefs.current[index] = ref)}
            >
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
            </div>
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
        "px-2 py-2 rounded-md transition w-full text-sm font-bold text-left",
        isHovered
          ? "text-blue-500 bg-slate-100 dark:bg-slate-800"
          : "bg-transparent"
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
