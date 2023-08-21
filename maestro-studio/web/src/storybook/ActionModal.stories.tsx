import ActionModal from "../components/device-and-device-elements/ActionModal";
import { DeviceProvider } from "../context/DeviceContext";
import { UIElement } from "../helpers/models";

export default {
  title: "ActionModal",
  parameters: {
    layout: "fullscreen",
  },
};

const defaultInspectedElement: UIElement = {
  id: "d40b317e-d656-48a7-b01a-f4a5e0575e0d",
  bounds: { x: 381, y: 735, width: 317, height: 308 },
  resourceId: "com.google.android.deskclock:id/timer_setup_digit_2",
  text: "2",
  hintText: "",
  accessibilityText: "",
};

export const Main = () => {
  return (
    <DeviceProvider defaultInspectedElement={defaultInspectedElement}>
      <div className="w-full h-full flex">
        <ActionModal onEdit={() => {}} onRun={() => {}} />
      </div>
    </DeviceProvider>
  );
};
