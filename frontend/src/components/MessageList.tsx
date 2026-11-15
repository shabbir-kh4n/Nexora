import { useEffect, useRef } from "react";
import { isActive, type Turn } from "../hooks/useConversation";
import { TurnView } from "./TurnView";

interface MessageListProps {
  turns: Turn[];
  onRetry: (turnId: string) => void;
}

const NEAR_BOTTOM_PX = 120;

export function MessageList({ turns, onRetry }: MessageListProps) {
  const scrollerRef = useRef<HTMLDivElement>(null);
  const followRef = useRef(true);
  const last = turns[turns.length - 1];

  useEffect(() => {
    const scroller = scrollerRef.current;
    if (scroller && followRef.current) scroller.scrollTo({ top: scroller.scrollHeight });
  }, [turns]);

  function handleScroll() {
    const scroller = scrollerRef.current;
    if (!scroller) return;
    // Stop following once the reader scrolls up, so streaming never fights them.
    followRef.current = scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < NEAR_BOTTOM_PX;
  }

  return (
    <div className="messages" ref={scrollerRef} onScroll={handleScroll}>
      <div className="messages__inner">
        {turns.map((turn) => (
          <TurnView key={turn.id} turn={turn} canRetry={turn === last && !isActive(turn)} onRetry={onRetry} />
        ))}
      </div>
    </div>
  );
}
