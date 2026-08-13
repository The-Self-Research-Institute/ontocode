import React, { useEffect, useRef } from 'react';

type MembershipFunctionType = 'singleton' | 'triangular' | 'trapezoidal' | 'gaussian' | 'sigmoid';

interface MembershipFunction {
  type: MembershipFunctionType;
  parameters: number[];
}

interface MembershipFunctionCanvasProps {
  membershipFunction: MembershipFunction;
  width?: number;
  height?: number;
  domain?: [number, number]; // [min, max] for x-axis
}

const MembershipFunctionCanvas: React.FC<MembershipFunctionCanvasProps> = ({
  membershipFunction,
  width = 400,
  height = 200,
  domain = [0, 100]
}) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    ctx.fillStyle = '#1e1e1e';
    ctx.fillRect(0, 0, width, height);

    drawGrid(ctx, width, height, domain);

    drawAxes(ctx, width, height);

    drawMembershipFunction(ctx, membershipFunction, width, height, domain);

    drawParameterMarkers(ctx, membershipFunction, width, height, domain);

  }, [membershipFunction, width, height, domain]);

  const drawGrid = (ctx: CanvasRenderingContext2D, w: number, h: number, [min, max]: [number, number]) => {
    ctx.strokeStyle = '#2d2d2d';
    ctx.lineWidth = 1;

    const xStep = (max - min) / 10;
    for (let i = 0; i <= 10; i++) {
      const x = (i / 10) * (w - 60) + 40;
      ctx.beginPath();
      ctx.moveTo(x, 20);
      ctx.lineTo(x, h - 30);
      ctx.stroke();
    }

    for (let i = 0; i <= 10; i++) {
      const y = (i / 10) * (h - 50) + 20;
      ctx.beginPath();
      ctx.moveTo(40, y);
      ctx.lineTo(w - 20, y);
      ctx.stroke();
    }
  };

  const drawAxes = (ctx: CanvasRenderingContext2D, w: number, h: number) => {
    ctx.strokeStyle = '#9ca3af';
    ctx.lineWidth = 2;
    ctx.font = '11px monospace';
    ctx.fillStyle = '#9ca3af';

    ctx.beginPath();
    ctx.moveTo(40, 20);
    ctx.lineTo(40, h - 30);
    ctx.stroke();

    ctx.beginPath();
    ctx.moveTo(40, h - 30);
    ctx.lineTo(w - 20, h - 30);
    ctx.stroke();

    ctx.fillText('1.0', 10, 25);
    ctx.fillText('0.5', 10, h / 2);
    ctx.fillText('0.0', 10, h - 25);

    const [min, max] = domain;
    ctx.fillText(String(min), 35, h - 10);
    ctx.fillText(String(Math.round((min + max) / 2)), w / 2 - 10, h - 10);
    ctx.fillText(String(max), w - 45, h - 10);
  };

  const drawMembershipFunction = (
    ctx: CanvasRenderingContext2D,
    func: MembershipFunction,
    w: number,
    h: number,
    [domainMin, domainMax]: [number, number]
  ) => {
    const padding = { left: 40, right: 20, top: 20, bottom: 30 };
    const graphWidth = w - padding.left - padding.right;
    const graphHeight = h - padding.top - padding.bottom;

    const xToCanvas = (x: number): number => {
      const normalized = (x - domainMin) / (domainMax - domainMin);
      return padding.left + normalized * graphWidth;
    };

    const yToCanvas = (y: number): number => {
      return padding.top + (1 - y) * graphHeight;
    };

    ctx.strokeStyle = '#a855f7';
    ctx.lineWidth = 3;
    ctx.beginPath();

    const steps = 200;
    for (let i = 0; i <= steps; i++) {
      const x = domainMin + (i / steps) * (domainMax - domainMin);
      const y = evaluateMembershipFunction(func, x, domainMin, domainMax);

      const canvasX = xToCanvas(x);
      const canvasY = yToCanvas(y);

      if (i === 0) {
        ctx.moveTo(canvasX, canvasY);
      } else {
        ctx.lineTo(canvasX, canvasY);
      }
    }

    ctx.stroke();

    ctx.lineTo(xToCanvas(domainMax), yToCanvas(0));
    ctx.lineTo(xToCanvas(domainMin), yToCanvas(0));
    ctx.closePath();
    ctx.fillStyle = 'rgba(168, 85, 247, 0.1)';
    ctx.fill();
  };

  const drawParameterMarkers = (
    ctx: CanvasRenderingContext2D,
    func: MembershipFunction,
    w: number,
    h: number,
    [domainMin, domainMax]: [number, number]
  ) => {
    const padding = { left: 40, right: 20, top: 20, bottom: 30 };
    const graphWidth = w - padding.left - padding.right;

    const xToCanvas = (x: number): number => {
      const normalized = (x - domainMin) / (domainMax - domainMin);
      return padding.left + normalized * graphWidth;
    };

    ctx.strokeStyle = '#6366f1';
    ctx.lineWidth = 1;
    ctx.setLineDash([5, 5]);
    ctx.font = '10px monospace';
    ctx.fillStyle = '#6366f1';

    switch (func.type) {
      case 'triangular': {
        const [a, b, c] = func.parameters;
        [a, b, c].forEach((val, idx) => {
          const x = xToCanvas(val);
          ctx.beginPath();
          ctx.moveTo(x, padding.top);
          ctx.lineTo(x, h - padding.bottom);
          ctx.stroke();
          ctx.fillText(['a', 'b', 'c'][idx], x - 5, h - padding.bottom + 15);
        });
        break;
      }

      case 'trapezoidal': {
        const [a, b, c, d] = func.parameters;
        [a, b, c, d].forEach((val, idx) => {
          const x = xToCanvas(val);
          ctx.beginPath();
          ctx.moveTo(x, padding.top);
          ctx.lineTo(x, h - padding.bottom);
          ctx.stroke();
          ctx.fillText(['a', 'b', 'c', 'd'][idx], x - 5, h - padding.bottom + 15);
        });
        break;
      }

      case 'gaussian': {
        const [mean] = func.parameters;
        const x = xToCanvas(mean);
        ctx.beginPath();
        ctx.moveTo(x, padding.top);
        ctx.lineTo(x, h - padding.bottom);
        ctx.stroke();
        ctx.fillText('μ', x - 5, h - padding.bottom + 15);
        break;
      }

      case 'sigmoid': {
        const [_, center] = func.parameters;
        const x = xToCanvas(center);
        ctx.beginPath();
        ctx.moveTo(x, padding.top);
        ctx.lineTo(x, h - padding.bottom);
        ctx.stroke();
        ctx.fillText('c', x - 5, h - padding.bottom + 15);
        break;
      }
    }

    ctx.setLineDash([]);
  };

  const evaluateMembershipFunction = (
    func: MembershipFunction,
    value: number,
    domainMin: number,
    domainMax: number
  ): number => {
    switch (func.type) {
      case 'singleton': {
        const [degree] = func.parameters;
        return degree || 0;
      }

      case 'triangular': {
        const [a, b, c] = func.parameters;
        if (value <= a || value >= c) return 0;
        if (value === b) return 1;
        if (value < b) return (value - a) / (b - a);
        return (c - value) / (c - b);
      }

      case 'trapezoidal': {
        const [a, b, c, d] = func.parameters;
        if (value <= a || value >= d) return 0;
        if (value >= b && value <= c) return 1;
        if (value < b) return (value - a) / (b - a);
        return (d - value) / (d - c);
      }

      case 'gaussian': {
        const [mean, sigma] = func.parameters;
        return Math.exp(-Math.pow(value - mean, 2) / (2 * Math.pow(sigma, 2)));
      }

      case 'sigmoid': {
        const [slope, center] = func.parameters;
        return 1 / (1 + Math.exp(-slope * (value - center)));
      }

      default:
        return 0;
    }
  };

  return (
    <div style={styles.container}>
      <canvas
        ref={canvasRef}
        width={width}
        height={height}
        style={styles.canvas}
      />
      <div style={styles.legend}>
        <span style={styles.legendItem}>
          <span style={{...styles.legendDot, backgroundColor: '#a855f7'}}></span>
          {membershipFunction.type} function
        </span>
        <span style={styles.legendItem}>
          <span style={{...styles.legendDot, backgroundColor: '#6366f1'}}></span>
          Parameters
        </span>
      </div>
    </div>
  );
};

const styles: { [key: string]: React.CSSProperties } = {
  container: {
    backgroundColor: '#1e1e1e',
    borderRadius: '8px',
    padding: '16px',
    border: '1px solid #374151',
  },
  canvas: {
    border: '1px solid #374151',
    borderRadius: '4px',
  },
  legend: {
    display: 'flex',
    gap: '16px',
    marginTop: '12px',
    fontSize: '12px',
    color: '#9ca3af',
    justifyContent: 'center',
  },
  legendItem: {
    display: 'flex',
    alignItems: 'center',
    gap: '6px',
  },
  legendDot: {
    width: '10px',
    height: '10px',
    borderRadius: '50%',
  },
};

export default MembershipFunctionCanvas;
