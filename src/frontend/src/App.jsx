import { useEffect, useState } from "react";

const API_BASE = (import.meta.env.VITE_API_BASE ?? "").replace(/\/$/, "");
const api = (p) => `${API_BASE}${p}`;

const DAY_NAMES = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const DAY_OPTIONS = [
  { v: 0, t: "Mon (周一)" }, { v: 1, t: "Tue (周二)" }, { v: 2, t: "Wed (周三)" },
  { v: 3, t: "Thu (周四)" }, { v: 4, t: "Fri (周五)" }, { v: 5, t: "Sat (周六)" }, { v: 6, t: "Sun (周日)" },
];

function InputRow({ onAdd }) {
  const [day, setDay] = useState(0);
  const [start, setStart] = useState("");
  const [end, setEnd] = useState("");
  const isHHMM = (t) => /^\d{2}:\d{2}$/.test(t);

  const add = () => {
    if (!isHHMM(start) || !isHHMM(end)) return onAdd(null,null,null,"请选择有效时间（HH:MM）");
    if (start >= end) return onAdd(null,null,null,"开始时间必须早于结束时间");
    onAdd(day, start, end);
    setStart(""); setEnd("");
  };

  return (
    <>
      <select className="select" value={day} onChange={e=>setDay(Number(e.target.value))}>
        {DAY_OPTIONS.map(o => <option key={o.v} value={o.v}>{o.t}</option>)}
      </select>
      <input className="time-input" type="time" value={start} onChange={e=>setStart(e.target.value)} />
      <input className="time-input" type="time" value={end} onChange={e=>setEnd(e.target.value)} />
      <button className="btn small" onClick={add}>Add</button>
    </>
  );
}

function Toast({ msg }) {
  return <div className={"toast" + (msg ? " show" : "")}>{msg}</div>;
}

export default function App() {
  const [pending, setPending] = useState([]);
  const [saved, setSaved]     = useState([]);
  const [toast, setToast]     = useState("");

  const show = (m) => { setToast(m); clearTimeout(show._t); show._t = setTimeout(()=>setToast(""), 1600); };
  const isHHMM = (t) => /^\d{2}:\d{2}$/.test(t);

  async function loadSaved() {
    try {
      const res = await fetch(api("/availability"), { headers:{Accept:"application/json"} });
      if (!res.ok) throw new Error(`HTTP ${res.status} ${await res.text().catch(()=> "")}`);
      const arr = await res.json();
      setSaved(Array.isArray(arr) ? arr.map(s => ({
        dayOfWeek: Number(s.dayOfWeek), startTime: s.startTime, endTime: s.endTime
      })) : []);
      show("Loaded.");
    } catch (e) {
      console.error(e); show("Network error while loading");
    }
  }

  async function saveToServer() {
    if (pending.length === 0) return show("Nothing to save.");
    for (const s of pending) {
      if (!(Number.isInteger(s.dayOfWeek) && s.dayOfWeek>=0 && s.dayOfWeek<=6 && isHHMM(s.startTime) && isHHMM(s.endTime) && s.startTime < s.endTime)) {
        return show("Invalid slot in pending.");
      }
    }
    try {
      const res = await fetch(api("/availability"), {
        method: "POST",
        headers: {"Content-Type":"application/json", "Accept":"application/json"},
        body: JSON.stringify({ driverId: 1, slots: pending })
      });
      const txt = await res.text();
      let msg = {};
      try { msg = JSON.parse(txt); } catch {}
      if (res.ok && msg.ok !== false) {
        setSaved(prev => [...prev, ...pending]);
        setPending([]);
        show("Saved!");
      } else {
        show(msg.error ? `Save failed: ${msg.error}` : `Save failed (HTTP ${res.status})`);
      }
    } catch (e) {
      console.error(e); show("Network error while saving.");
    }
  }

  async function deleteSaved(slot) {
    try {
      const qs = new URLSearchParams({
        dayOfWeek: String(slot.dayOfWeek),
        startTime: slot.startTime,
        endTime:   slot.endTime
      });
      const res = await fetch(api("/availability?" + qs.toString()), {
        method: "DELETE",
        headers: {Accept:"application/json"}
      });
      const txt = await res.text();
      let msg = {}; try { msg = JSON.parse(txt); } catch {}
      if (res.ok && msg.ok) {
        setSaved(prev => prev.filter(s =>
          !(s.dayOfWeek===slot.dayOfWeek && s.startTime===slot.startTime && s.endTime===slot.endTime)
        ));
        show("Deleted.");
      } else {
        show(msg.error ? `Delete failed: ${msg.error}` : `Delete failed (HTTP ${res.status})`);
      }
    } catch (e) {
      console.error(e); show("Network error while deleting.");
    }
  }

  function onAdd(day, start, end, err) {
    if (err) return show(err);
    setPending(p => [...p, { dayOfWeek: day, startTime: start, endTime: end }]);
  }
  function removePending(idx) { setPending(p => p.filter((_,i)=> i!==idx)); }

  useEffect(()=>{ loadSaved(); }, []);

  const items = [
    ...saved.map(s => ({...s, _saved:true})),
    ...pending.map((s,i)=> ({...s, _saved:false, _pidx:i}))
  ];

  return (
    <div className="container">
      <h1>Driver Weekly Availability <small className="muted">React</small></h1>

      <section className="grid">
        <InputRow onAdd={onAdd} />
      </section>

      <div className="actions">
        <button className="btn primary" onClick={saveToServer}>Save</button>
        <button className="btn" onClick={loadSaved}>Reload</button>
        <button className="btn" onClick={()=>setPending([])}>Clear pending</button>
      </div>

      <section className="card">
        <h2>Current Week Slots</h2>
        <ul className="slot-list">
          {items.length===0 && <p className="muted">No slots yet. Add some above.</p>}
          {items.map((it, idx)=>(
            <li key={idx} className="slot-item">
              <div className="slot-meta">
                <span className={"badge " + (it._saved ? "saved":"pending")}>
                  {it._saved ? "saved":"pending"}
                </span>
                <span>{DAY_NAMES[it.dayOfWeek]} {it.startTime}–{it.endTime}</span>
              </div>
              {it._saved
                ? <button className="btn small" onClick={()=>deleteSaved(it)}>Delete</button>
                : <button className="btn small" onClick={()=>removePending(it._pidx)}>Delete</button>}
            </li>
          ))}
        </ul>
      </section>

      <Toast msg={toast} />
    </div>
  );
}
