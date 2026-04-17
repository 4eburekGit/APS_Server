--
-- PostgreSQL database dump
--

\restrict R6O7klgJCF6v2veg5rUcVcfFahFJLeGOJebE90QIhEZN53uzrcwbFpk1RKHIrrJ

BEGIN TRANSACTION

-- Dumped from database version 16.11 (Ubuntu 16.11-0ubuntu0.24.04.1)
-- Dumped by pg_dump version 16.11 (Ubuntu 16.11-0ubuntu0.24.04.1)

-- Started on 2026-04-17 08:28:00 MSK

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- TOC entry 216 (class 1259 OID 16631)
-- Name: folders; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE IF NOT EXISTS public.folders (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name character varying(255) NOT NULL,
    parent_folder_id uuid,
    owner_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


ALTER TABLE public.folders OWNER TO postgres;

--
-- TOC entry 217 (class 1259 OID 16650)
-- Name: metadata; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE IF NOT EXISTS public.metadata (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    filename character varying(255) NOT NULL,
    content_type character varying(100),
    size bigint NOT NULL,
    storage_path character varying(500) NOT NULL,
    uploaded_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    owner_id uuid NOT NULL,
    folder_id uuid
);


ALTER TABLE public.metadata OWNER TO postgres;

--
-- TOC entry 215 (class 1259 OID 16620)
-- Name: users; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE IF NOT EXISTS public.users (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    username character varying(255) NOT NULL,
    password character varying(255) NOT NULL,
    role character varying(50) DEFAULT 'USER'::character varying NOT NULL
);


ALTER TABLE public.users OWNER TO postgres;

--
-- TOC entry 3303 (class 2606 OID 16637)
-- Name: folders folders_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.folders
    ADD CONSTRAINT folders_pkey PRIMARY KEY (id);


--
-- TOC entry 3311 (class 2606 OID 16658)
-- Name: metadata metadata_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.metadata
    ADD CONSTRAINT metadata_pkey PRIMARY KEY (id);


--
-- TOC entry 3307 (class 2606 OID 16639)
-- Name: folders uq_folder_name_parent_owner; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.folders
    ADD CONSTRAINT uq_folder_name_parent_owner UNIQUE (name, parent_folder_id, owner_id);


--
-- TOC entry 3299 (class 2606 OID 16628)
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- TOC entry 3301 (class 2606 OID 16630)
-- Name: users users_username_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_username_key UNIQUE (username);


--
-- TOC entry 3308 (class 1259 OID 16670)
-- Name: idx_file_metadata_folder_id; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_file_metadata_folder_id ON public.metadata USING btree (folder_id);


--
-- TOC entry 3309 (class 1259 OID 16669)
-- Name: idx_file_metadata_owner_id; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_file_metadata_owner_id ON public.metadata USING btree (owner_id);


--
-- TOC entry 3304 (class 1259 OID 16671)
-- Name: idx_folder_owner_id; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_folder_owner_id ON public.folders USING btree (owner_id);


--
-- TOC entry 3305 (class 1259 OID 16672)
-- Name: idx_folder_parent_id; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_folder_parent_id ON public.folders USING btree (parent_folder_id);


--
-- TOC entry 3314 (class 2606 OID 16664)
-- Name: metadata fk_file_folder; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.metadata
    ADD CONSTRAINT fk_file_folder FOREIGN KEY (folder_id) REFERENCES public.folders(id) ON DELETE SET NULL;


--
-- TOC entry 3315 (class 2606 OID 16659)
-- Name: metadata fk_file_owner; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.metadata
    ADD CONSTRAINT fk_file_owner FOREIGN KEY (owner_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- TOC entry 3312 (class 2606 OID 16640)
-- Name: folders fk_folder_owner; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.folders
    ADD CONSTRAINT fk_folder_owner FOREIGN KEY (owner_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- TOC entry 3313 (class 2606 OID 16645)
-- Name: folders fk_parent_folder; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.folders
    ADD CONSTRAINT fk_parent_folder FOREIGN KEY (parent_folder_id) REFERENCES public.folders(id) ON DELETE CASCADE;


-- Completed on 2026-04-17 08:28:00 MSK

COMMIT;

--
-- PostgreSQL database dump complete
--

\unrestrict R6O7klgJCF6v2veg5rUcVcfFahFJLeGOJebE90QIhEZN53uzrcwbFpk1RKHIrrJ

