import * as React from "react";
import { VariantProps, cva } from "class-variance-authority";
import clsx from "clsx";
import { twMerge } from "tailwind-merge";
import * as IconList from "react-icons/ri";

const iconVariants = cva("", {
  variants: {
    size: {
      "12": "h-3 w-3 min-w-[12px]",
      "14": "h-3.5 w-3.5 min-w-[14px]",
      "16": "h-4 w-4 min-w-[16px]",
      "18": "h-[18px] w-[18px] min-w-[18px]",
      "20": "h-5 w-5 min-w-[20px]",
      "24": "h-6 w-6 min-w-[24px]",
      "28": "h-7 w-7 min-w-[28px]",
      "32": "h-8 w-8 min-w-[32px]",
    },
  },
  defaultVariants: {
    size: "20",
  },
});

export interface IconProps
  extends React.SVGAttributes<SVGElement>,
    VariantProps<typeof iconVariants> {
  iconName: keyof typeof IconList;
}

function Icon({ className, iconName, size, ...props }: IconProps) {
  const IconComponent = IconList[iconName];
  return (
    <IconComponent
      className={twMerge(clsx(iconVariants({ size, className })))}
      {...props}
    />
  );
}

export { Icon, iconVariants, IconList };
