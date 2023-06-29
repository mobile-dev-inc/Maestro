import { SaveFlowModal } from "../components/commands/SaveFlowModal";
import { FormattedFlow } from "../helpers/models";

export default {
  title: "SaveFlowModal",
};

const formattedFlow: FormattedFlow = {
  config: "appId: com.example.app",
  commands:
    "- launchApp:\n" +
    "    appId: com.example.app\n" +
    "    clearState: true\n" +
    "    clearKeychain: true\n" +
    "- tapOn: 'I am over 18'\n" +
    "- tapOn: 'LOGIN'\n" +
    "- waitForAnimationToEnd\n" +
    "- inputText: 'nativetest@nativetest.com'\n" +
    "- tapOn:\n" +
    "    id: 'Password'\n" +
    "- inputText: 'Native1234!'\n" +
    "- tapOn: 'Login'\n" +
    "- tapOn:\n" +
    "    text: Allow\n" +
    "    optional: true\n" +
    "- assertVisible:\n" +
    "    id: 'mybets'" +
    "- launchApp:\n" +
    "    appId: com.example.app\n" +
    "    clearState: true\n" +
    "    clearKeychain: true\n" +
    "- tapOn: 'I am over 18'\n" +
    "- tapOn: 'LOGIN'\n" +
    "- waitForAnimationToEnd\n" +
    "- inputText: 'nativetest@nativetest.com'\n" +
    "- tapOn:\n" +
    "    id: 'Password'\n" +
    "- inputText: 'Native1234!'\n" +
    "- tapOn: 'Login'\n" +
    "- tapOn:\n" +
    "    text: Allow\n" +
    "    optional: true\n" +
    "- assertVisible:" +
    "- launchApp:\n" +
    "    appId: com.example.app\n" +
    "    clearState: true\n" +
    "    clearKeychain: true\n" +
    "- tapOn: 'I am over 18'\n" +
    "- tapOn: 'LOGIN'\n" +
    "- waitForAnimationToEnd\n" +
    "- inputText: 'nativetest@nativetest.com'\n" +
    "- tapOn:\n" +
    "    id: 'Password'\n" +
    "- inputText: 'Native1234!'\n" +
    "- tapOn: 'Login'\n" +
    "- tapOn:\n" +
    "    text: Allow\n" +
    "    optional: true\n" +
    "- assertVisible:",
};

export const Main = () => {
  return <SaveFlowModal formattedFlow={formattedFlow} onClose={() => {}} />;
};
