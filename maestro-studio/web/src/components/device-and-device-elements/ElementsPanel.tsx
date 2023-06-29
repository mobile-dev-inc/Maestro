import React, { Fragment, useMemo, useState } from "react";
import { Button } from "../design-system/button";
import { Input } from "../design-system/input";
import { DeviceScreen, UIElement } from "../../helpers/models";
import clsx from "clsx";

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
  const [query, setQuery] = useState<string>("");

  const sortedElements: UIElement[] = useMemo(() => {
    const filteredElements = deviceScreen.elements.filter((element) => {
      if (!element.text && !element.resourceId) return false;

      const modifiedText = element.text?.replace(/0/g, "");
      const modifiedResourceId = element.resourceId?.replace(/0/g, "");

      return (
        !query ||
        modifiedText?.toLowerCase().includes(query.toLowerCase()) ||
        modifiedResourceId?.toLowerCase().includes(query.toLowerCase()) ||
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
    <div className="relative min-w-[264px] max-w-[264px] border-r border-slate-200 dark:border-slate-800 h-full overflow-visible z-10 flex flex-col">
      <Button
        onClick={closePanel}
        variant="tertiary"
        icon="RiCloseLine"
        className="rounded-full absolute top-6 -right-4"
      />
      <div className="px-8 py-6 border-b border-slate-200 dark:border-slate-800">
        <Input
          onChange={(e) => setQuery(e.target.value)}
          size="sm"
          leftIcon="RiSearchLine"
          placeholder="Text or Id"
          className="w-full rounded-md"
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
                  query={query}
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
                  query={query}
                  text={item.text as string}
                  elementType="text"
                />
              )}
            </Fragment>
          );
        })}
      </div>
    </div>
  );
}

interface ElementListItemProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  query: string;
  text: string;
  elementType: "id" | "text";
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
  const textParts: string[] = text.split(query);
  return (
    <button
      className={clsx(
        "px-2 py-1.5 bg-transparent hover:bg-slate-100 dark:hover:bg-slate-800 rounded-md transition w-full",
        isHovered && "text-blue-500"
      )}
      {...rest}
    >
      <div className="max-w-full flex gap-2">
        <p className="truncate text-sm font-bold">
          {textParts.map((part, index) => (
            <>
              {index < textParts.length - 1 ? (
                <>
                  <span>{part}</span>
                  <span className="text-purple-500 dark:text-purple-400">
                    {query}
                  </span>
                </>
              ) : (
                part
              )}
            </>
          ))}
        </p>
        <p className="text-sm font-bold text-gray-400 whitespace-nowrap">
          • {elementType}
        </p>
      </div>
    </button>
  );
};
