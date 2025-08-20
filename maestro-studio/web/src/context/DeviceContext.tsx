import {
  createContext,
  useContext,
  ReactNode,
  useState,
  useEffect,
  useRef,
} from "react";
import { UIElement, DeviceScreen } from "../helpers/models";
import { API } from "../api/api";
import _ from "lodash";

interface DeviceContextType {
  isLoading: boolean;
  hoveredElement: UIElement | null;
  setHoveredElement: (element: UIElement | null) => void;
  inspectedElement: UIElement | null;
  setInspectedElement: (id: UIElement | null) => void;
  deviceScreen: DeviceScreen | undefined;
  footerHint: string | null;
  setFooterHint: (id: string | null) => void;
  currentCommandValue: string;
  setCurrentCommandValue: (id: string) => void;
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
  const { deviceScreen: fetchedDeviceScreen, error } = API.useDeviceScreen();

  const prevDeviceScreenRef = useRef<DeviceScreen | undefined>();
  const [deviceScreenState, setDeviceScreenState] =
    useState<DeviceScreen | undefined>();
  const [isLoading, setIsLoading] = useState(true);
  const [hoveredElement, setHoveredElement] = useState<UIElement | null>(null);
  const [inspectedElement, setInspectedElement] = useState<UIElement | null>(
    defaultInspectedElement
  );
  const [footerHint, setFooterHint] = useState<string | null>(null);
  const [currentCommandValue, setCurrentCommandValue] = useState<string>("");

  /**
   * Update device only when it is changed.
   * This is to stop unnecessary renders inside the app
   */
  useEffect(() => {
    if (fetchedDeviceScreen) {
      // Check if other device data (besides screenshot) has changed before setting it
      const { screenshot: screenshotFetched, ...restOfFetchedData } =
        fetchedDeviceScreen;
      let restOfPrevData = {};
      if (prevDeviceScreenRef.current) {
        const { screenshot: __, ...restData } = prevDeviceScreenRef.current;
        restOfPrevData = restData;
      }
      if (!_.isEqual(restOfPrevData, restOfFetchedData)) {
        setDeviceScreenState(fetchedDeviceScreen);
        prevDeviceScreenRef.current = fetchedDeviceScreen;
      }
    }
  }, [fetchedDeviceScreen]);

  /**
   * It currently loading, set loading false when deviceScreenState or error is received
   */
  useEffect(() => {
    if (isLoading) {
      if (deviceScreenState || error) {
        setIsLoading(false);
      }
    }
  }, [deviceScreenState, error, isLoading]);

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
        deviceScreen: deviceScreenState,
        currentCommandValue,
        setCurrentCommandValue,
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
