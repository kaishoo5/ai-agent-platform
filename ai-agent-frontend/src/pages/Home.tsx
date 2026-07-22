import { useEffect, useState } from "react";
import api from "../api/api";

function Home() {

    const [message, setMessage] = useState("");

    useEffect(() => {

        api.get("/api/health")
            .then((res) => {
                setMessage(res.data.status);
            })
            .catch((err) => {
                console.error(err);
            });

    }, []);

    return (
        <div>
            <h1>AI Agent</h1>

            <h2>{message}</h2>
        </div>
    );
}

export default Home;