import {
  createContext,
  useContext,
  ReactNode,
  useState,
  useEffect,
} from "react";
import { UIElement, DeviceScreen } from "../helpers/models";
import { API } from "../api/api";

interface DeviceContextType {
  isLoading: boolean;
  hoveredElement: UIElement | null;
  setHoveredElement: (element: UIElement | null) => void;
  inspectedElement: UIElement | null;
  setInspectedElement: (id: UIElement | null) => void;
  deviceScreen: DeviceScreen | undefined;
  footerHint: string | null;
  setFooterHint: (id: string | null) => void;
}

const DeviceContext = createContext<DeviceContextType | undefined>(undefined);

interface DeviceProviderProps {
  children: ReactNode;
  defaultInspectedElement?: UIElement;
}

export const DeviceProvider: React.FC<DeviceProviderProps> = ({
  children,
  defaultInspectedElement = null, // Default value
}) => {
  const { deviceScreen, error }: { deviceScreen?: DeviceScreen; error?: any } =
    API.useDeviceScreen();
  const [isLoading, setIsLoading] = useState(true);
  const [hoveredElement, setHoveredElement] = useState<UIElement | null>(null);
  const [inspectedElement, setInspectedElement] = useState<UIElement | null>(
    defaultInspectedElement
  );
  const [footerHint, setFooterHint] = useState<string | null>(null);

  useEffect(() => {
    if (deviceScreen || error) {
      setIsLoading(false);
    }
  }, [deviceScreen, error]);

  return (
    <DeviceContext.Provider
      value={{
        isLoading,
        hoveredElement,
        setHoveredElement,
        inspectedElement,
        setInspectedElement,
        footerHint,
        setFooterHint,
        deviceScreen,
      }}
    >
      {children}
    </DeviceContext.Provider>
  );
};

export const useDeviceContext = () => {
  const context = useContext(DeviceContext);
  if (context === undefined) {
    throw new Error("useDeviceContext must be used within a DeviceProvider");
  }
  return context;
};
