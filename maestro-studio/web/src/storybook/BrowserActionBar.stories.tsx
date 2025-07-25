import BrowserActionBar from "../components/device-and-device-elements/BrowserActionBar";
import {useEffect, useState} from "react";

export default {
  title: "BrowserActionBar",
};

export const Main = () => {
  const [currentUrl, setCurrentUrl] = useState<string>("https://google.com");
  const [isLoading, setIsLoading] = useState<boolean>(false);
  useEffect(() => {
    const interval = setInterval(() => {
      setCurrentUrl(old => {
        // if no scheme, add https
        if (!old.startsWith("http")) {
          old = "https://" + old;
        }
        const url = new URL(old);
        url.searchParams.set("ts", Date.now().toString());
        return url.toString();
      });
    }, 1000);
    return () => clearInterval(interval);
  }, []);
  const onUrlUpdated = (url: string) => {
    setIsLoading(true);
    setTimeout(() => {
      setCurrentUrl(url);
      setIsLoading(false);
    }, 1000);
  }
  return (
    <div className="flex flex-col w-full">
      <BrowserActionBar
        currentUrl={currentUrl}
        onUrlUpdated={onUrlUpdated}
        isLoading={isLoading}
      />
    </div>
  );
};
