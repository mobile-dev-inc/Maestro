import PageSwitcher from "../components/common/PageSwitcher";
import { useState } from "react";
import Banner from "../components/common/Banner";

export default {
  title: "PageSwitcher",
};

export const Main = () => {
  const [showSecond, setShowSecond] = useState(false);

  const banner = (
    <Banner
      left={"LOG IN / JOIN WIKIPEDIA"}
      right={"org.wikipedia:id/positiveButton"}
      onClose={() => setShowSecond(false)}
    />
  );

  const page1 = <div className="p-8">First Page</div>;

  const page2 = (
    <div className="p-8 font-bold">
      Here are some examples of how you can interact with this element:
    </div>
  );

  return (
    <div className="w-full h-full">
      <button onClick={() => setShowSecond(!showSecond)}>
        {showSecond ? "hide" : "show"}
      </button>
      <PageSwitcher banner={showSecond ? banner : null}>
        {page1}
        {showSecond ? page2 : null}
      </PageSwitcher>
    </div>
  );
};
