/*
  OSHI V2 — Vanilla JS:
  1. Animated mesh canvas background (lightweight, ~60fps)
  2. Scroll-reveal observer
  3. Sticky nav shrink + active link
  4. Bento card cursor-tracked glow
  5. Mobile menu toggle
*/

// ===== 1. Mesh canvas =====
(() => {
  const canvas = document.getElementById('mesh-bg');
  if (!canvas) return;
  const ctx = canvas.getContext('2d', { alpha: true });
  const dpr = Math.min(window.devicePixelRatio || 1, 2);

  let w = 0, h = 0, nodes = [];
  const DENSITY = 0.00006;   // nodes per px²
  const SPEED = 0.18;
  const LINK_DIST = 160;     // px

  function resize() {
    w = canvas.clientWidth = window.innerWidth;
    h = canvas.clientHeight = window.innerHeight;
    canvas.width = w * dpr;
    canvas.height = h * dpr;
    ctx.scale(dpr, dpr);
    spawn();
  }

  function spawn() {
    const count = Math.max(30, Math.floor(w * h * DENSITY));
    nodes = Array.from({ length: count }, () => ({
      x: Math.random() * w,
      y: Math.random() * h,
      vx: (Math.random() - 0.5) * SPEED,
      vy: (Math.random() - 0.5) * SPEED,
      r: 1 + Math.random() * 1.4,
    }));
  }

  function step() {
    ctx.clearRect(0, 0, w, h);

    // Move
    for (const n of nodes) {
      n.x += n.vx;
      n.y += n.vy;
      if (n.x < 0 || n.x > w) n.vx *= -1;
      if (n.y < 0 || n.y > h) n.vy *= -1;
    }

    // Links
    for (let i = 0; i < nodes.length; i++) {
      const a = nodes[i];
      for (let j = i + 1; j < nodes.length; j++) {
        const b = nodes[j];
        const dx = a.x - b.x;
        const dy = a.y - b.y;
        const d2 = dx*dx + dy*dy;
        if (d2 < LINK_DIST * LINK_DIST) {
          const d = Math.sqrt(d2);
          const alpha = (1 - d / LINK_DIST) * 0.22;
          // Gradient stroke from brand-1 to brand-2 based on x position
          const t = (a.x + b.x) / 2 / w;
          const r = Math.round(124 + (176 - 124) * t);
          const g = Math.round(139 + (106 - 139) * t);
          const bb = Math.round(255 + (210 - 255) * t);
          ctx.strokeStyle = `rgba(${r},${g},${bb},${alpha})`;
          ctx.lineWidth = 1;
          ctx.beginPath();
          ctx.moveTo(a.x, a.y);
          ctx.lineTo(b.x, b.y);
          ctx.stroke();
        }
      }
    }

    // Nodes
    ctx.fillStyle = 'rgba(124,139,255,0.55)';
    for (const n of nodes) {
      ctx.beginPath();
      ctx.arc(n.x, n.y, n.r, 0, Math.PI * 2);
      ctx.fill();
    }

    requestAnimationFrame(step);
  }

  // Honor reduced-motion: render once, no animation loop.
  const prefersReduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  resize();
  window.addEventListener('resize', () => { ctx.setTransform(1,0,0,1,0,0); resize(); });
  if (!prefersReduce) requestAnimationFrame(step);
  else { /* draw a single frame */ step(); }
})();

// ===== 2. Reveal on scroll =====
(() => {
  const els = document.querySelectorAll('.reveal');
  if (!('IntersectionObserver' in window) || els.length === 0) {
    els.forEach(el => el.classList.add('in'));
    return;
  }
  const io = new IntersectionObserver((entries) => {
    entries.forEach(entry => {
      if (entry.isIntersecting) {
        entry.target.classList.add('in');
        io.unobserve(entry.target);
      }
    });
  }, { threshold: 0.1, rootMargin: '0px 0px -40px 0px' });
  els.forEach((el, i) => {
    el.style.transitionDelay = `${Math.min(i * 30, 200)}ms`;
    io.observe(el);
  });
})();

// ===== 3. Sticky nav shrink =====
(() => {
  const nav = document.getElementById('nav');
  if (!nav) return;
  let last = 0;
  const tick = () => {
    const y = window.scrollY;
    nav.classList.toggle('shrunk', y > 20);
    last = y;
  };
  document.addEventListener('scroll', tick, { passive: true });
  tick();
})();

// ===== 4. Bento card cursor-tracked glow =====
(() => {
  const cards = document.querySelectorAll('.bento-card');
  cards.forEach(card => {
    card.addEventListener('pointermove', e => {
      const rect = card.getBoundingClientRect();
      const mx = ((e.clientX - rect.left) / rect.width) * 100;
      const my = ((e.clientY - rect.top) / rect.height) * 100;
      card.style.setProperty('--mx', `${mx}%`);
      card.style.setProperty('--my', `${my}%`);
    });
  });
})();

// ===== 5. Mobile menu =====
(() => {
  const burger = document.getElementById('navBurger');
  const links = document.querySelector('.nav-links');
  if (!burger || !links) return;
  burger.addEventListener('click', () => {
    links.classList.toggle('open');
  });
  links.addEventListener('click', e => {
    if (e.target.tagName === 'A') links.classList.remove('open');
  });
})();
