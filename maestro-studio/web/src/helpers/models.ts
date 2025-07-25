import React from "react";

export type HTMLProps<T> = React.DetailedHTMLProps<React.HTMLAttributes<T>, T>;
export type TextAreaProps = React.DetailedHTMLProps<
  React.TextareaHTMLAttributes<HTMLTextAreaElement>,
  HTMLTextAreaElement
>;
export type DivProps = HTMLProps<HTMLDivElement>;

export type UIElementBounds = {
  x: number;
  y: number;
  width: number;
  height: number;
};

export type UIElement = {
  id: string;
  bounds?: UIElementBounds;
  resourceId?: string;
  resourceIdIndex?: number;
  text?: string;
  hintText?: string;
  textIndex?: number;
  accessibilityText?: string;
};

export type DeviceScreen = {
  platform: string;
  screenshot: string;
  width: number;
  height: number;
  elements: UIElement[];
  url?: string;
};

export type ReplCommandStatus =
  | "pending"
  | "running"
  | "success"
  | "error"
  | "canceled";

export type ReplCommand = {
  id: string;
  yaml: string;
  status: ReplCommandStatus;
};

export type Repl = {
  commands: ReplCommand[];
};

export type FormattedFlow = {
  config: string;
  commands: string;
};

export type BannerMessage = {
  level: "info" | "warning" | "error" | "none";
  message: string;
};

export type AttributesType = {
  accessibilityText?: string;
  bounds?: string;
  checked?: string;
  enabled?: string;
  focused?: string;
  hintText?: string;
  "resource-id"?: string;
  selected?: string;
  text?: string;
  title?: string;
  value?: string;
};

export type ViewHierarchyType = {
  attributes: AttributesType;
  checked: boolean;
  enabled: boolean;
  focused: boolean;
  selected: boolean;
  children?: ViewHierarchyType[];
};

export type AiResponseType = {
  command?: string;
};

export type AuthType = {
  authToken: string;
  openAiToken: string;
};
